// Step6 验收（AC-053/054，mock 供应商不烧钱）：
// 正常搜索单列计费 / 主家挂自动降级带标记 / 缓存命中不二次扣费 / 全挂 502 / deep_read 转 Markdown / 空钱包 402。
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
process.env.SEARCH_MOCK = "true"; // mock 主/备链（真实 bocha/searxng 未配 env 自动不启用）
process.env.SEARCH_COST_CENTS = "10";
process.env.DEEPREAD_COST_CENTS = "20";

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

function search(token: string, body: object) {
  return request(app.getHttpServer())
    .post("/api/v1/gateway/search")
    .set("Authorization", `Bearer ${token}`)
    .send(body);
}

async function balanceOf(token: string) {
  const r = await request(app.getHttpServer()).get("/api/v1/balance").set("Authorization", `Bearer ${token}`);
  return (r.body.data as { total_cents: number }).total_cents;
}

describe("搜索网关（P02 Step6）", () => {
  it("正常搜索：结果返回 + 明细单列「联网搜索」扣 10 分", async () => {
    const { token, userId } = await newUser("13844440001");
    await app.get(BillingService).credit({ userId, amountCents: 1000, kind: 2, idempotencyKey: "s-1" });
    const res = await search(token, { intent: "web", query: "rust 教程", nonce: "nonce-s-0001" });
    expect(res.status).toBe(200);
    expect(res.body.data.provider).toBe("mock-primary");
    expect(res.body.data.degraded).toBe(false);
    expect(res.body.data.results[0].title).toContain("rust 教程");
    expect(await balanceOf(token)).toBe(1000 - 10);

    const tx = await request(app.getHttpServer()).get("/api/v1/balance/transactions").set("Authorization", `Bearer ${token}`);
    const item = (tx.body.data.items as { model: string | null; kind_label: string }[])[0];
    expect(item.model).toBe("web_search"); // 明细单列
    expect(item.kind_label).toBe("消费");
  });

  it("主家挂 → 自动降级到备家，响应带 degraded=true", async () => {
    const { token, userId } = await newUser("13844440002");
    await app.get(BillingService).credit({ userId, amountCents: 1000, kind: 2, idempotencyKey: "s-2" });
    const res = await search(token, { intent: "web", query: "[dead-main]查点东西", nonce: "nonce-s-0002" });
    expect(res.status).toBe(200);
    expect(res.body.data.provider).toBe("mock-backup");
    expect(res.body.data.degraded).toBe(true);
    expect(res.body.data.results[0].snippet).toContain("备用");
  });

  it("缓存命中：同 query 第二次不扣费、cached=true（且大小写/空格归一）", async () => {
    const { token, userId } = await newUser("13844440003");
    await app.get(BillingService).credit({ userId, amountCents: 1000, kind: 2, idempotencyKey: "s-3" });
    await search(token, { intent: "web", query: "缓存测试词", nonce: "nonce-s-0003" }).expect(200);
    const hit = await search(token, { intent: "web", query: "  缓存测试词 ", nonce: "nonce-s-0004" }); // 归一化后同 key
    expect(hit.status).toBe(200);
    expect(hit.body.data.cached).toBe(true);
    expect(await balanceOf(token)).toBe(1000 - 10); // 只扣第一次
  });

  it("主备全挂 → 502 大白话", async () => {
    const { token, userId } = await newUser("13844440004");
    await app.get(BillingService).credit({ userId, amountCents: 1000, kind: 2, idempotencyKey: "s-4" });
    const res = await search(token, { intent: "web", query: "[dead-all]都挂了", nonce: "nonce-s-0005" });
    expect(res.status).toBe(502);
    expect(res.body.msg).toContain("不可用");
    expect(await balanceOf(token)).toBe(1000); // 失败不计费
  });

  it("deep_read：URL 转 Markdown + 扣 20 分", async () => {
    const { token, userId } = await newUser("13844440005");
    await app.get(BillingService).credit({ userId, amountCents: 1000, kind: 2, idempotencyKey: "s-5" });
    const res = await search(token, { intent: "deep_read", query: "https://example.com/article", nonce: "nonce-s-0006" });
    expect(res.status).toBe(200);
    expect(res.body.data.markdown).toContain("# https://example.com/article");
    expect(await balanceOf(token)).toBe(1000 - 20);
  });

  it("空钱包 → 402；未登录 → 401", async () => {
    const { token } = await newUser("13844440006");
    const r = await search(token, { intent: "web", query: "没钱也想搜", nonce: "nonce-s-0007" });
    expect(r.status).toBe(402);
    const anon = await request(app.getHttpServer()).post("/api/v1/gateway/search").send({ intent: "web", query: "匿名", nonce: "nonce-s-0008" });
    expect(anon.status).toBe(401);
  });
});
