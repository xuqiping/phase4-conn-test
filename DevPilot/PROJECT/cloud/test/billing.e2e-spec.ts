// Step4 验收（AC-045 服务端半边）：余额/明细/预估 + 扣费三保险（乐观锁不超扣 / 幂等键不重扣 / 账本=钱包对账）。
import { INestApplication, ValidationPipe } from "@nestjs/common";
import { Test } from "@nestjs/testing";
import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import request from "supertest";
import { AppModule } from "../src/app.module";
import { DbService } from "../src/db/db.service";
import { BillingService } from "../src/billing/billing.service";
import { LedgerService } from "../src/billing/ledger.service";
import { WalletService } from "../src/billing/wallet.service";

process.env.JWT_ACCESS_SECRET = "test-access";
process.env.JWT_REFRESH_SECRET = "test-refresh";
process.env.GIFT_CENTS_ON_REGISTER = "0";
process.env.GIFT_ENABLED = "false"; // 本套关体验金，让明细行数断言确定（0 元赠送也会占一行账）

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

async function balanceOf(token: string) {
  const r = await request(app.getHttpServer()).get("/api/v1/balance").set("Authorization", `Bearer ${token}`);
  return r.body.data as { balance_cents: number; gift_cents: number; total_cents: number };
}

describe("钱包与账本（P02 Step4）", () => {
  it("余额接口：注册后 0 体验金（本套关闭）+ 充值入账走 credit", async () => {
    const { token, userId } = await newUser("13811110001");
    expect((await balanceOf(token)).total_cents).toBe(0);
    const billing = app.get(BillingService);
    await billing.credit({ userId, amountCents: 1000, kind: 2, idempotencyKey: "r-1" }); // 模拟充值 10 元
    const b = await balanceOf(token);
    expect(b.balance_cents).toBe(1000);
    expect(b.total_cents).toBe(1000);
  });

  it("扣费顺序：先扣体验金再扣充值余额", async () => {
    const db = app.get(DbService);
    const { userId } = await newUser("13811110002");
    await db.query(`UPDATE wallets SET gift_cents = 300, balance_cents = 500 WHERE user_id = $1`, [userId]);
    await app.get(BillingService).charge({ userId, amountCents: 400, nonce: "t1" });
    const w = await app.get(WalletService).get(userId);
    expect(Number(w.gift_cents)).toBe(0); // 体验金 300 先扣光
    expect(Number(w.balance_cents)).toBe(400); // 充值补扣 100
  });

  it("幂等：同 nonce 双扣只生效一次，返回首次结果", async () => {
    const { userId } = await newUser("13811110003");
    const billing = app.get(BillingService);
    await billing.credit({ userId, amountCents: 1000, kind: 2, idempotencyKey: "r-2" });
    const a = await billing.charge({ userId, amountCents: 200, nonce: "same" });
    const b = await billing.charge({ userId, amountCents: 200, nonce: "same" });
    expect(a.id).toBe(b.id); // 同一条账
    const w = await app.get(WalletService).get(userId);
    expect(Number(w.balance_cents)).toBe(800); // 只扣一次
  });

  it("并发 100 笔同扣：不超扣（乐观锁），余额不为负", async () => {
    const { userId } = await newUser("13811110004");
    await app.get(WalletService).credit(userId, 5000); // 只够扣 50 笔 ×100
    const billing = app.get(BillingService);
    const results = await Promise.allSettled(
      Array.from({ length: 100 }, (_, i) =>
        billing.charge({ userId, amountCents: 100, nonce: `conc-${i}` }),
      ),
    );
    const ok = results.filter((r) => r.status === "fulfilled").length;
    const rejected = results.filter((r) => r.status === "rejected");
    expect(ok).toBe(50);
    expect(rejected.length).toBe(50);
    const w = await app.get(WalletService).get(userId);
    expect(Number(w.balance_cents)).toBe(0); // 恰好扣光，绝不为负
  });

  it("余额不足 → 402 大白话（charge 侧）", async () => {
    const { userId } = await newUser("13811110005");
    await expect(
      app.get(BillingService).charge({ userId, amountCents: 999999, nonce: "x" }),
    ).rejects.toMatchObject({ status: 402, message: expect.stringContaining("余额不足") });
  });

  it("对账：账本推导总额 = 钱包 balance+gift", async () => {
    const { userId } = await newUser("13811110006");
    const wallet = app.get(WalletService);
    const billing = app.get(BillingService);
    await billing.credit({ userId, amountCents: 10000, kind: 2, idempotencyKey: "r-3" });
    await db_chargeMany(billing, userId, 7, 111); // 7 笔各扣 111
    const w = await wallet.get(userId);
    const derived = await app.get(LedgerService).deriveTotalCents(userId);
    expect(derived).toBe(Number(w.balance_cents) + Number(w.gift_cents));
    expect(derived).toBe(10000 - 7 * 111);
  });

  it("明细分页：字段齐全 + 分页正确（新→旧）", async () => {
    const { token, userId } = await newUser("13811110007");
    const billing = app.get(BillingService);
    await app.get(WalletService).credit(userId, 100000);
    for (let i = 0; i < 25; i++) {
      await billing.charge({ userId, amountCents: 10, nonce: `page-${i}` });
    }
    const r1 = await request(app.getHttpServer())
      .get("/api/v1/balance/transactions?page=1&pageSize=20")
      .set("Authorization", `Bearer ${token}`);
    expect(r1.status).toBe(200);
    expect(r1.body.data.total).toBe(25);
    expect(r1.body.data.items.length).toBe(20);
    expect(r1.body.data.items[0].kind_label).toBe("消费");
    expect(r1.body.data.items[0].amount_cents).toBe(-10);
    const r2 = await request(app.getHttpServer())
      .get("/api/v1/balance/transactions?page=2&pageSize=20")
      .set("Authorization", `Bearer ${token}`);
    expect(r2.body.data.items.length).toBe(5);
  });

  it("预估：单价表算价 + sufficient 判定；未知模型 400", async () => {
    const { token, userId } = await newUser("13811110008");
    await app.get(WalletService).credit(userId, 105);
    const ok = await request(app.getHttpServer())
      .post("/api/v1/gateway/estimate")
      .set("Authorization", `Bearer ${token}`)
      .send({ model: "gpt-4o-mini", input_tokens: 1_000_000, output_tokens: 0 });
    expect(ok.status).toBe(200);
    expect(ok.body.data.cost_cents_est).toBe(105);
    expect(ok.body.data.sufficient).toBe(true);

    const poor = await request(app.getHttpServer())
      .post("/api/v1/gateway/estimate")
      .set("Authorization", `Bearer ${token}`)
      .send({ model: "gpt-4o", input_tokens: 1_000_000, output_tokens: 0 });
    expect(poor.body.data.cost_cents_est).toBe(2100);
    expect(poor.body.data.sufficient).toBe(false);

    const unknown = await request(app.getHttpServer())
      .post("/api/v1/gateway/estimate")
      .set("Authorization", `Bearer ${token}`)
      .send({ model: "no-such-model", input_tokens: 1, output_tokens: 1 });
    expect(unknown.status).toBe(400);
    expect(unknown.body.msg).toContain("暂不支持");
  });

  it("未登录访问计费接口 → 401", async () => {
    const r = await request(app.getHttpServer()).get("/api/v1/balance");
    expect(r.status).toBe(401);
  });
});

async function db_chargeMany(billing: BillingService, userId: number, n: number, cents: number) {
  for (let i = 0; i < n; i++) {
    await billing.charge({ userId, amountCents: cents, nonce: `recon-${i}` });
  }
}
