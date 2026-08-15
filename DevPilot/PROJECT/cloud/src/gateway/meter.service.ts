// 权威计量：以上游末帧 usage 为准入账（不信客户端），幂等键 = chat:{uid}:{client_nonce}。
// 含单任务消费熔断（security：超配置上限中断）。
import { HttpException, HttpStatus, Injectable } from "@nestjs/common";
import { BillingService } from "../billing/billing.service";
import { LedgerService } from "../billing/ledger.service";
import { estimateCostCents } from "../billing/pricing";
import { WalletService } from "../billing/wallet.service";

@Injectable()
export class MeterService {
  constructor(
    private billing: BillingService,
    private wallet: WalletService,
    private ledger: LedgerService,
  ) {}

  /** 调用前余额预估检查：不足直接 402（防白嫖流式跑完才拒付）。 */
  async precheck(userId: number, model: string, inputTokens: number) {
    const est = estimateCostCents(model, inputTokens, 0);
    const w = await this.wallet.get(userId);
    const total = Number(w.balance_cents) + Number(w.gift_cents);
    if (total < Math.max(est, 1)) {
      // 至少要有 1 分钱——完全空钱包直接拦
      throw new HttpException("余额不足，请充值后再试", HttpStatus.PAYMENT_REQUIRED);
    }
    return { est_cents: est, balance_total_cents: total };
  }

  /** 流结束后按权威 usage 实扣；返回本单费用与任务累计（熔断判断用）。 */
  async settle(input: {
    userId: number;
    nonce: string; // 请求级：幂等键原料
    taskId: string; // 任务级：熔断累计口径
    model: string;
    usage: { input_tokens: number; output_tokens: number };
  }): Promise<{ cost_cents: number; task_spent_cents: number; capped: boolean }> {
    const cost = estimateCostCents(input.model, input.usage.input_tokens, input.usage.output_tokens);
    await this.billing.charge({
      userId: input.userId,
      amountCents: cost,
      model: input.model,
      tokensIn: input.usage.input_tokens,
      tokensOut: input.usage.output_tokens,
      taskId: input.taskId,
      nonce: `chat:${input.nonce}`,
    });
    const taskSpent = await this.ledger.deriveTaskSpentCents(input.userId, input.taskId);
    const cap = Number(process.env.CHAT_TASK_CAP_CENTS ?? 500_00); // 默认单任务 500 元
    return { cost_cents: cost, task_spent_cents: taskSpent, capped: taskSpent >= cap };
  }
}
