// Step5 验收（AC-053 主链，mock 上游不烧钱）：
// 正常流→末帧权威入账；同 nonce 重放不重扣；空钱包 402；上游挂→SSE error 502；单任务熔断 capped。
import { INestApplication, ValidationPipe } from "@nestjs/common";
import { Test } from "@nestjs/testing";
import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import request from "supertest";
import { AppModule } from "../src/app.module";
import { DbService } from "../src/db/db.service";
import { BillingService } from "../src/billing/billing.service";
import { WalletService } from "../src/billing/wallet.service";

process.env.JWT_ACCESS_SECRET = "test-access";
process.env.JWT_REFRESH_SECRET = "test-refresh";
process.env.GIFT_ENABLED = "false";
process.env.GATEWAY_PROVIDER = "mock";
process.env.CHAT_TASK_CAP_CENTS = "500"; // 熔断阈值 5 元（mock 单次 315 分，两次触顶）

let app: INestApplication;

beforeAll(async () => {
  const moduleRef = await Test.createTestingModule({ imports: [AppModule] })
    .overrideProvider(DbService)
    .useFactory({
      factory: async () => {
        process.env.PGLITE_DIR = ":memory:";
        const db = new DbService();
        const dir = join(__dirname, "..", "flyway");
        for (const f of readdirSync(dir).filter((x) => x.endsWith(".sql")).sort()) {
          await db.exec(readFileSync(join(dir, f), "utf8"));
        }
        return db;
      },
    })
    .compile();
  app = moduleRef.createNestApplication();
  app.setGlobalPrefix("api/v1");
  app.useGlobalPipes(new ValidationPipe({ whitelist: true, transform: true }));
  await app.init();
});

afterAll(async () => app.close());

async function newUser(phone: string) {
  await request(app.getHttpServer()).post("/api/v1/auth/send-code").send({ phone });
  const res = await request(app.getHttpServer())
    .post("/api/v1/auth/register")
    .send({ phone, code: "123456", deviceId: `dev-${phone}` });
  return { token: res.body.data.token as string, userId: res.body.data.user.id as number };
}

function chatReq(token: string, body: object) {
  return request(app.getHttpServer())
    .post("/api/v1/gateway/chat")
    .set("Authorization", `Bearer ${token}`)
    .send(body);
}

/** 解析 SSE 文本为 {event → data[]} */
function parseSse(text: string) {
  const events: { event: string; data: Record<string, unknown> }[] = [];
  for (const block of text.split("\n\n")) {
    const ev = /event: (.*)/.exec(block)?.[1];
    const raw = /data: (.*)/.exec(block)?.[1];
    if (ev && raw) {
      try { events.push({ event: ev, data: JSON.parse(raw) }); } catch { /* ignore */ }
    }
  }
  return events;
}

describe("模型网关 chat（P02 Step5）", () => {
  const body = (model = "gpt-4o-mini", nonce = "nonce-aaaa-1", content = "你好") => ({
    model,
    messages: [{ role: "user", content }],
    nonce,
  });

  it("正常流：SSE 逐块透传 + 末帧权威 usage 入账 315 分", async () => {
    const { token, userId } = await newUser("13822220001");
    await app.get(BillingService).credit({ userId, amountCents: 10000, kind: 2, idempotencyKey: "c-1" });
    const res = await chatReq(token, body()).expect(200);
    expect(res.headers["content-type"]).toContain("text/event-stream");
    const events = parseSse(res.text);
    const deltas = events.filter((e) => e.event === "delta");
    expect(deltas.length).toBeGreaterThan(3); // 逐字透传，不是憋一屏
    expect((deltas[0].data as { text: string }).text).toBe("收");
    const done = events.find((e) => e.event === "done")!.data as { cost_cents: number; capped: boolean };
    expect(done.cost_cents).toBe(315); // 1M in(105) + 0.5M out(210)，权威计量
    const w = await app.get(WalletService).get(userId);
    expect(Number(w.balance_cents)).toBe(10000 - 315);
  });

  it("同 nonce 重放：SSE 照常回完但不重扣", async () => {
    const { token, userId } = await newUser("13822220002");
    await app.get(BillingService).credit({ userId, amountCents: 10000, kind: 2, idempotencyKey: "c-2" });
    await chatReq(token, body("gpt-4o-mini", "nonce-bbbb-1")).expect(200);
    await chatReq(token, body("gpt-4o-mini", "nonce-bbbb-1")).expect(200); // 网络重试同 nonce
    const w = await app.get(WalletService).get(userId);
    expect(Number(w.balance_cents)).toBe(10000 - 315); // 只扣一次
  });

  it("空钱包 → 402 先拒付（流都不开）", async () => {
    const { token } = await newUser("13822220003");
    const res = await chatReq(token, body());
    expect(res.status).toBe(402);
    expect(res.body.msg).toContain("余额不足");
  });

  it("上游挂 → SSE error 事件 502 语义，不透传上游原文", async () => {
    const { token, userId } = await newUser("13822220004");
    await app.get(BillingService).credit({ userId, amountCents: 10000, kind: 2, idempotencyKey: "c-4" });
    const res = await chatReq(token, body("gpt-4o-mini", "nonce-dddd-1", "[dead]模拟上游宕机")).expect(200);
    const err = parseSse(res.text).find((e) => e.event === "error")!.data as { code: number; msg: string };
    expect(err.code).toBe(502);
    expect(err.msg).toContain("不可用");
  });

  it("单任务熔断：同 task_id 两轮累计 ≥ 上限 → capped=true", async () => {
    const { token, userId } = await newUser("13822220005");
    await app.get(BillingService).credit({ userId, amountCents: 100000, kind: 2, idempotencyKey: "c-5" });
    const round = (nonce: string) =>
      chatReq(token, { ...body("gpt-4o-mini", nonce), task_id: "task-cccc-999" }).expect(200);
    // 第一轮 315 < 500 未触顶
    const r1 = await round("nonce-cccc-r1");
    expect((parseSse(r1.text).find((e) => e.event === "done")!.data as { capped: boolean }).capped).toBe(false);
    // 同任务第二轮累计 630 ≥ 500 触顶（客户端收到 capped 后应停跑）
    const r2 = await round("nonce-cccc-r2");
    const done2 = parseSse(r2.text).find((e) => e.event === "done")!.data as { capped: boolean; task_spent_cents: number };
    expect(done2.capped).toBe(true);
    expect(done2.task_spent_cents).toBe(630);
  });
});
