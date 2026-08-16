// 充值服务：下单（pack 档位配置文件，不进库）+ mock 渠道回调入账。
// 幂等双保险：订单 status CAS（0→1 只许一次）+ ledger 幂等键 recharge-{orderId}。
import { BadRequestException, Injectable, NotFoundException } from "@nestjs/common";
import { createHmac, timingSafeEqual } from "node:crypto";
import { DbService } from "../../db/db.service";
import { BillingService } from "../billing.service";

export interface RechargePack {
  code: string;
  label: string;
  amount_cents: number;
  bonus_cents: number;
}

/** 档位配置（plan：MVP 三档；真渠道接商户资质后替换 mock） */
export const PACKS: RechargePack[] = [
  { code: "P50", label: "50 元档", amount_cents: 5000, bonus_cents: 0 },
  { code: "P200", label: "200 元档", amount_cents: 20000, bonus_cents: 500 },
  { code: "P500", label: "500 元档", amount_cents: 50000, bonus_cents: 2000 },
];

export interface RechargeOrder {
  id: number;
  pack_code: string;
  amount_cents: string;
  bonus_cents: string;
  status: number; // 0待支付 1已支付
}

@Injectable()
export class PaymentService {
  constructor(
    private db: DbService,
    private billing: BillingService,
  ) {}

  packOf(code: string): RechargePack {
    const pack = PACKS.find((p) => p.code === code);
    if (!pack) throw new BadRequestException(`没有这个充值档位：${code}`);
    return pack;
  }

  async createOrder(userId: number, packCode: string) {
    const pack = this.packOf(packCode);
    const res = await this.db.query<RechargeOrder>(
      `INSERT INTO recharge_orders (user_id, pack_code, amount_cents, bonus_cents, channel)
       VALUES ($1,$2,$3,$4,'mock') RETURNING id, pack_code, amount_cents, bonus_cents, status`,
      [userId, pack.code, pack.amount_cents, pack.bonus_cents],
    );
    const order = res.rows[0];
    return {
      order_id: order.id,
      pack_code: order.pack_code,
      pay_amount_cents: Number(order.amount_cents),
      bonus_cents: Number(order.bonus_cents),
      status: order.status,
      // mock 渠道：直接回一个「模拟支付页」URL（e2e 拿签名去打 webhook）
      pay_url: `mock://pay/${order.id}`,
    };
  }

  /** 生成 mock 渠道签名（HMAC-SHA256，密钥走环境变量；真渠道换成渠道公钥验签） */
  signMock(payload: { order_id: number; trade_no: string; paid_cents: number }): string {
    const secret = process.env.PAYMENT_HMAC_SECRET;
    if (!secret) {
      // 配置级风险（审查发现）：默认密钥=任何人可自签回调伪造充值。生产必配，缺配直接拒。
      if (process.env.NODE_ENV === "production") {
        throw new BadRequestException("支付验签密钥未配置，回调服务不可用");
      }
      return createHmac("sha256", "dev-mock-secret").update(canonical(payload)).digest("hex");
    }
    return createHmac("sha256", secret).update(canonical(payload)).digest("hex");
  }

  verify(payload: { order_id: number; trade_no: string; paid_cents: number }, signature: string): boolean {
    const expected = Buffer.from(this.signMock(payload), "hex");
    let got: Buffer;
    try {
      got = Buffer.from(signature, "hex");
    } catch {
      return false;
    }
    return expected.length === got.length && timingSafeEqual(expected, got);
  }

  /** 回调入账：验签在 controller；这里做金额一致性 + 订单 CAS + 幂等入账。 */
  async handlePaid(orderId: number, tradeNo: string, paidCents: number): Promise<{ first: boolean; credited_cents: number }> {
    const found = await this.db.query<RechargeOrder & { user_id: number }>(
      `SELECT id, user_id, pack_code, amount_cents, bonus_cents, status FROM recharge_orders WHERE id = $1`,
      [orderId],
    );
    const order = found.rows[0];
    if (!order) throw new NotFoundException("订单不存在");
    if (Number(order.amount_cents) !== paidCents) {
      throw new BadRequestException("回调金额与订单不符");
    }

    // CAS：status 0→1 只许一个并发请求赢；输家=重放回调，直接幂等返回
    const won = await this.db.query(
      `UPDATE recharge_orders SET status = 1, trade_no = $1, paid_at = now()
       WHERE id = $2 AND status = 0 RETURNING id`,
      [tradeNo, orderId],
    );
    if (won.rows.length === 0) {
      // 崩溃窗口补偿（BUG-P02-02）：CAS 赢后、credit 前进程崩溃 → 订单 status=1 但账本无
      // recharge-{orderId} 行，用户付了钱永不到账。重放回调时发现该状态就补入账。
      const ledgerRow = await this.db.query<{ id: number }>(
        `SELECT id FROM token_ledger WHERE idempotency_key = $1`,
        [`recharge-${orderId}`],
      );
      if (ledgerRow.rows.length === 0) {
        const o = (
          await this.db.query<{ user_id: number; amount: string; bonus: string; pack: string }>(
            `SELECT user_id, amount_cents AS amount, bonus_cents AS bonus, pack_code AS pack FROM recharge_orders WHERE id = $1`,
            [orderId],
          )
        ).rows[0];
        const total = Number(o.amount) + Number(o.bonus);
        await this.billing.credit({
          userId: o.user_id,
          amountCents: total,
          kind: 2,
          idempotencyKey: `recharge-${orderId}`,
          note: o.pack,
        });
        return { first: true, credited_cents: total }; // 补账成功，视同首账
      }
      return { first: false, credited_cents: 0 };
    }

    const got = await this.db.query<{ user_id: number; amount: string; bonus: string; pack: string }>(
      `SELECT user_id, amount_cents AS amount, bonus_cents AS bonus, pack_code AS pack FROM recharge_orders WHERE id = $1`,
      [orderId],
    );
    const o = got.rows[0];
    const total = Number(o.amount) + Number(o.bonus);
    await this.billing.credit({
      userId: o.user_id,
      amountCents: total, // 本金+赠送一起入账（明细里 pack_code 备注）
      kind: 2,
      idempotencyKey: `recharge-${orderId}`,
      note: o.pack,
    });
    return { first: true, credited_cents: total };
  }
}

/** 规范化序列化（键排序），签名双方按同一规则拼串。 */
function canonical(p: Record<string, unknown>): string {
  return JSON.stringify(Object.keys(p).sort().map((k) => [k, p[k]]));
}
