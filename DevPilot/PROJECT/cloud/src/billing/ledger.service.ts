// 账本服务：只增不改（append-only，审计基座）。余额可由账本推导，钱包只是缓存。
import { Injectable } from "@nestjs/common";
import { DbService } from "../db/db.service";

export interface LedgerRow {
  id: number;
  user_id: number;
  task_id: string | null;
  kind: 1 | 2 | 3 | 4; // 1消费 2充值 3赠送 4人工调整
  model: string | null;
  tokens_in: number;
  tokens_out: number;
  amount_cents: string; // BIGINT 经 PGlite 序列化为字符串
  idempotency_key: string;
  created_at: string;
}

export const KIND_LABEL: Record<number, string> = {
  1: "消费",
  2: "充值",
  3: "赠送",
  4: "人工调整",
};

@Injectable()
export class LedgerService {
  constructor(private db: DbService) {}

  /** 追加一条账（重复幂等键抛 23505，由调用方决定重试或回读首果）。 */
  async append(row: {
    userId: number;
    kind: LedgerRow["kind"];
    amountCents: number;
    idempotencyKey: string;
    taskId?: string | null;
    model?: string | null;
    tokensIn?: number;
    tokensOut?: number;
  }): Promise<LedgerRow> {
    const res = await this.db.query<LedgerRow>(
      `INSERT INTO token_ledger (user_id, task_id, kind, model, tokens_in, tokens_out, amount_cents, idempotency_key)
       VALUES ($1,$2,$3,$4,$5,$6,$7,$8) RETURNING *`,
      [
        row.userId, row.taskId ?? null, row.kind, row.model ?? null,
        row.tokensIn ?? 0, row.tokensOut ?? 0, row.amountCents, row.idempotencyKey,
      ],
    );
    return res.rows[0];
  }

  async findByIdempotencyKey(key: string): Promise<LedgerRow | null> {
    const res = await this.db.query<LedgerRow>(
      `SELECT * FROM token_ledger WHERE idempotency_key = $1`, [key],
    );
    return res.rows[0] ?? null;
  }

  /** 分页明细（新→旧）。单条 SQL 取页 + 单条 COUNT，无 N+1。 */
  async listByUser(userId: number, page: number, pageSize: number) {
    const items = await this.db.query<LedgerRow>(
      `SELECT * FROM token_ledger WHERE user_id = $1
       ORDER BY created_at DESC, id DESC LIMIT $2 OFFSET $3`,
      [userId, pageSize, (page - 1) * pageSize],
    );
    const total = await this.db.query<{ n: string }>(
      `SELECT COUNT(*) AS n FROM token_ledger WHERE user_id = $1`, [userId],
    );
    return { items: items.rows, total: Number(total.rows[0].n) };
  }

  /** 单任务累计消费（分，熔断判断用；task_id = 客户端 nonce）。 */
  async deriveTaskSpentCents(userId: number, taskId: string): Promise<number> {
    const res = await this.db.query<{ s: string | null }>(
      `SELECT COALESCE(SUM(-amount_cents), 0) AS s FROM token_ledger
       WHERE user_id = $1 AND task_id = $2 AND kind = 1`, [userId, taskId],
    );
    return Number(res.rows[0].s);
  }

  /** 对账：账本推导的总额（分，可负）——应恒等于 wallets.balance+gift。 */
  async deriveTotalCents(userId: number): Promise<number> {
    const res = await this.db.query<{ s: string | null }>(
      `SELECT COALESCE(SUM(amount_cents), 0) AS s FROM token_ledger WHERE user_id = $1`, [userId],
    );
    return Number(res.rows[0].s);
  }
}
