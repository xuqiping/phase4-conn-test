// Step7 验收：下单→回调→余额增（本金+赠送）；回调重放不加钱；验签失败 400；金额篡改 400。
import { INestApplication, ValidationPipe } from "@nestjs/common";
import { Test } from "@nestjs/testing";
import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import request from "supertest";
import { AppModule } from "../src/app.module";
import { DbService } from "../src/db/db.service";
import { PaymentService } from "../src/billing/payment/payment.service";
import { WalletService } from "../src/billing/wallet.service";

process.env.JWT_ACCESS_SECRET = "test-access";
process.env.JWT_REFRESH_SECRET = "test-refresh";
process.env.GIFT_ENABLED = "false";
process.env.PAYMENT_HMAC_SECRET = "test-hmac-secret";

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

async function balanceCents(token: string) {
  const r = await request(app.getHttpServer()).get("/api/v1/balance").set("Authorization", `Bearer ${token}`);
  return (r.body.data as { total_cents: number }).total_cents;
}

describe("充值（P02 Step7，mock 渠道）", () => {
  it("下单 P200 → 回调（验签过）→ 余额 +20500（本金 20000 + 赠 500）", async () => {
    const { token, userId } = await newUser("13833330001");
    const order = await request(app.getHttpServer())
      .post("/api/v1/payments/recharge").set("Authorization", `Bearer ${token}`)
      .send({ pack_code: "P200" });
    expect(order.status).toBe(200);
    const orderId = order.body.data.order_id as number;

    const payload = { order_id: orderId, trade_no: "mock-trade-0001", paid_cents: 20000 };
    const sig = app.get(PaymentService).signMock(payload);
    const hook = await request(app.getHttpServer())
      .post("/api/v1/payments/webhook").send(payload).set("x-signature", sig);
    expect(hook.status).toBe(200);
    expect(hook.body.data.first).toBe(true);
    expect(await balanceCents(token)).toBe(20500);

    // 明细里单列 kind=2，备注 pack_code（联动点 2）
    const tx = await request(app.getHttpServer()).get("/api/v1/balance/transactions").set("Authorization", `Bearer ${token}`);
    const item = (tx.body.data.items as { kind_label: string; amount_cents: number; note: string | null }[]).at(-1)!;
    expect(item.kind_label).toBe("充值");
    expect(item.amount_cents).toBe(20500);
    expect(item.note).toBe("P200");
  });

  it("回调重放（同单再打一次）→ 不重复加钱", async () => {
    const { token } = await newUser("13833330002");
    const order = await request(app.getHttpServer())
      .post("/api/v1/payments/recharge").set("Authorization", `Bearer ${token}`)
      .send({ pack_code: "P50" });
    const orderId = order.body.data.order_id as number;
    const payload = { order_id: orderId, trade_no: "mock-trade-0002", paid_cents: 5000 };
    const sig = app.get(PaymentService).signMock(payload);
    await request(app.getHttpServer()).post("/api/v1/payments/webhook").send(payload).set("x-signature", sig).expect(200);
    const replay = await request(app.getHttpServer()).post("/api/v1/payments/webhook").send(payload).set("x-signature", sig);
    expect(replay.status).toBe(200);
    expect(replay.body.data.first).toBe(false); // 订单已支付，幂等返回
    expect(await balanceCents(token)).toBe(5000); // 只加一次
  });

  it("验签失败 → 400（伪造回调进不来）", async () => {
    const r = await request(app.getHttpServer())
      .post("/api/v1/payments/webhook")
      .send({ order_id: 999, trade_no: "evil-trade", paid_cents: 1 })
      .set("x-signature", "deadbeef");
    expect(r.status).toBe(400);
    expect(r.body.msg).toContain("验签失败");
  });

  it("签名对但金额被改小 → 400（金额一致性校验）", async () => {
    const { token } = await newUser("13833330003");
    const order = await request(app.getHttpServer())
      .post("/api/v1/payments/recharge").set("Authorization", `Bearer ${token}`)
      .send({ pack_code: "P500" });
    const orderId = order.body.data.order_id as number;
    // 用「正确金额」生成签名，但请求里塞改小的金额 → 签名对不上，验签先拦
    const signed = app.get(PaymentService).signMock({ order_id: orderId, trade_no: "fake-trade-no", paid_cents: 50000 });
    const r = await request(app.getHttpServer())
      .post("/api/v1/payments/webhook")
      .send({ order_id: orderId, trade_no: "fake-trade-no", paid_cents: 1 })
      .set("x-signature", signed);
    expect(r.status).toBe(400);
    // 再用「改小金额」自签 → 签名过但金额一致性拦
    const sig2 = app.get(PaymentService).signMock({ order_id: orderId, trade_no: "fake-trade-no", paid_cents: 1 });
    const r2 = await request(app.getHttpServer())
      .post("/api/v1/payments/webhook")
      .send({ order_id: orderId, trade_no: "fake-trade-no", paid_cents: 1 })
      .set("x-signature", sig2);
    expect(r2.status).toBe(400);
    expect(r2.body.msg).toContain("金额与订单不符");
    expect(await balanceCents(token)).toBe(0);
  });

  it("非法档位 → 400；未登录下单 → 401", async () => {
    const { token } = await newUser("13833330004");
    const bad = await request(app.getHttpServer())
      .post("/api/v1/payments/recharge").set("Authorization", `Bearer ${token}`)
      .send({ pack_code: "P1" });
    expect(bad.status).toBe(400);
    const anon = await request(app.getHttpServer()).post("/api/v1/payments/recharge").send({ pack_code: "P50" });
    expect(anon.status).toBe(401);
  });
  it("BUG-P02-02 回归：CAS 赢后入账前崩溃（模拟：手工置 status=1 但不记账）→ 重放回调补账到账", async () => {
    const { token, userId } = await newUser("13833330006");
    const order = (
      await request(app.getHttpServer())
        .post("/api/v1/payments/recharge")
        .set("authorization", `Bearer ${token}`)
        .send({ pack_code: "P50" })
    ).body.data;
    // 模拟崩溃窗口：只把订单置为已支付，绕过 handlePaid 不入账
    await app.get(DbService).query(
      `UPDATE recharge_orders SET status = 1, paid_at = now() WHERE id = $1`, [order.order_id],
    );
    const w0 = await app.get(WalletService).get(userId);
    const before = Number(w0.balance_cents) + Number(w0.gift_cents);
    // 重放回调（合法签名）→ 补账分支命中
    const payload = { order_id: order.order_id, trade_no: "crash-window-1", paid_cents: order.pay_amount_cents };
    const sig = app.get(PaymentService).signMock(payload);
    await request(app.getHttpServer())
      .post("/api/v1/payments/webhook")
      .set("x-signature", sig)
      .send(payload)
      .expect(200);
    const w1 = await app.get(WalletService).get(userId);
    expect(Number(w1.balance_cents) + Number(w1.gift_cents)).toBe(before + order.pay_amount_cents);
    // 再重放一次：不重复加钱
    await request(app.getHttpServer())
      .post("/api/v1/payments/webhook")
      .set("x-signature", sig)
      .send(payload)
      .expect(200);
    const w2 = await app.get(WalletService).get(userId);
    expect(Number(w2.balance_cents) + Number(w2.gift_cents)).toBe(before + order.pay_amount_cents);
  });
});
