// 统一扣费入口：幂等 + 账本 + 钱包三合一。网关/搜索/充值等一切动钱的路径必须走这里。
// TODO(生产)：切真 PG 后把「扣钱包 + 记账本」包进同一个事务；PGlite 单连接下由
// 幂等键 UNIQUE + 乐观锁兜底，乱序极端窗口由回滚补偿（见 catch 分支）。
import { Injectable } from "@nestjs/common";
import { LedgerService, LedgerRow } from "./ledger.service";
import { WalletService } from "./wallet.service";

@Injectable()
export class BillingService {
  constructor(
    private ledger: LedgerService,
    private wallet: WalletService,
  ) {}

  /**
   * 消费扣费（kind=1，负数入账）。同 nonce 重复调用返回首次结果，不重扣。
   * 顺序：查幂等 → 锁扣钱包 → 记账本；账本撞 UNIQUE（并发同 nonce）则补回钱包并返回首果。
   */
  async charge(input: {
    userId: number;
    amountCents: number; // 正数
    model?: string;
    tokensIn?: number;
    tokensOut?: number;
    taskId?: string | null;
    nonce: string; // 调用方生成的一次性键（如 `chat-${taskId}-${round}`）
    /** 流式末帧实扣用（BUG-P02-01）：服务已跑完，余额不足也透支记账，绝不白嫖 */
    allowOverdraw?: boolean;
  }): Promise<LedgerRow> {
    const key = `charge:${input.userId}:${input.nonce}`;
    const existing = await this.ledger.findByIdempotencyKey(key);
    if (existing) return existing;

    if (input.amountCents > 0) {
      await this.wallet.deduct(input.userId, input.amountCents, input.allowOverdraw === true);
    }
    try {
      return await this.ledger.append({
        userId: input.userId,
        kind: 1,
        amountCents: -input.amountCents, // 消费为负，推导余额时与充值/赠送直接相加
        idempotencyKey: key,
        taskId: input.taskId ?? null,
        model: input.model ?? null,
        tokensIn: input.tokensIn ?? 0,
        tokensOut: input.tokensOut ?? 0,
      });
    } catch (e: unknown) {
      const dup = (e as { code?: string }).code === "23505";
      if (dup) {
        // 并发同 nonce：另一路已扣过 → 补回本路钱包，返回首果
        if (input.amountCents > 0) await this.wallet.credit(input.userId, input.amountCents);
        const first = await this.ledger.findByIdempotencyKey(key);
        if (first) return first;
      }
      throw e;
    }
  }

  /**
   * 入账（充值 kind=2 / 人工调整 kind=4）：账本 + 钱包同走，保证「账本推导=钱包」恒成立。
   * 幂等键由调用方给（如 `recharge-${orderId}`），重复回调不重发。
   */
  async credit(input: {
    userId: number;
    amountCents: number;
    kind: 2 | 4;
    idempotencyKey: string;
    note?: string | null;
  }): Promise<LedgerRow> {
    const existing = await this.ledger.findByIdempotencyKey(input.idempotencyKey);
    if (existing) return existing;
    const row = await this.ledger.append({
      userId: input.userId,
      kind: input.kind,
      amountCents: input.amountCents,
      idempotencyKey: input.idempotencyKey,
      model: input.note ?? null,
    });
    await this.wallet.credit(input.userId, input.amountCents);
    return row;
  }
}
