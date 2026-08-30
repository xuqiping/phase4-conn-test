// 钱包服务：余额缓存 + 乐观锁扣费。只增账本在 LedgerService，本服务负责钱包侧一致性。
import { HttpException, HttpStatus, Injectable } from "@nestjs/common";
import { DbService } from "../db/db.service";

export interface Wallet {
  user_id: number;
  balance_cents: string;
  gift_cents: string;
  version: number;
}

const MAX_RETRY = 12; // 100 并发压测下 CAS 连败兜底：每次重试都会重读最新余额，最终要么赢要么 402

@Injectable()
export class WalletService {
  private locks = new Map<number, Promise<unknown>>();
  constructor(private db: DbService) {}

  async get(userId: number): Promise<Wallet> {
    const res = await this.db.query<Wallet>(
      `SELECT user_id, balance_cents, gift_cents, version FROM wallets WHERE user_id = $1`, [userId],
    );
    if (!res.rows[0]) throw new HttpException("钱包不存在", HttpStatus.INTERNAL_SERVER_ERROR);
    return res.rows[0];
  }

  /**
   * 扣费（正数金额，分）：先扣体验金再扣充值余额。
   * 乐观锁：UPDATE ... WHERE version=旧值，0 行命中（并发改动）最多重试 3 次。
   * 余额不足抛 402（前端引导充值）。
   */
  async deduct(userId: number, amountCents: number, allowOverdraw = false): Promise<void> {
    // 进程内按用户串行（PGlite 单连接本就单写者；多进程/生产靠下方乐观锁 CAS 兜底），
    // 避免 100 并发下乐观锁重试互相踩踏导致饥饿。
    const prev = this.locks.get(userId) ?? Promise.resolve();
    const run = prev.then(
      () => this.deductInner(userId, amountCents, allowOverdraw),
      () => this.deductInner(userId, amountCents, allowOverdraw),
    );
    this.locks.set(
      userId,
      run.then(
        () => {},
        () => {},
      ),
    );
    return run;
  }

  private async deductInner(userId: number, amountCents: number, allowOverdraw: boolean): Promise<void> {
    for (let i = 0; i < MAX_RETRY; i++) {
      const w = await this.get(userId);
      const balance = Number(w.balance_cents);
      const gift = Number(w.gift_cents);
      // allowOverdraw：流式服务已跑完的末帧实扣（BUG-P02-01）——宁可钱包透支也必须记账，
      // 否则余额略不足的用户可换 nonce 无限白嫖整条流；透支后 precheck 会拦后续请求。
      if (!allowOverdraw && balance + gift < amountCents) {
        throw new HttpException("余额不足，请充值后再试", HttpStatus.PAYMENT_REQUIRED);
      }
      const giftUse = Math.min(gift, amountCents);
      const updated = await this.db.query<{ version: number }>(
        `UPDATE wallets
         SET gift_cents = gift_cents - $1,
             balance_cents = balance_cents - $2,
             version = version + 1
         WHERE user_id = $3 AND version = $4
         RETURNING version`,
        [giftUse, amountCents - giftUse, userId, w.version],
      );
      if (updated.rows.length > 0) return; // CAS 赢：本机版本已落库
    }
    throw new HttpException("扣费繁忙，请重试", HttpStatus.CONFLICT);
  }

  /** 充值入账（只加充值余额，不动体验金）。 */
  async credit(userId: number, amountCents: number): Promise<void> {
    await this.db.query(
      `UPDATE wallets SET balance_cents = balance_cents + $1, version = version + 1 WHERE user_id = $2`,
      [amountCents, userId],
    );
  }
}
