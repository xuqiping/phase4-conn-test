// 计费接口：余额 / 明细分页。默认需登录（全局 JwtGuard）。
import { Controller, Get, Query, UseGuards } from "@nestjs/common";
import { IsInt, Max, Min } from "class-validator";
import { Type } from "class-transformer";
import { CurrentUser } from "../auth/jwt.guard";
import { R } from "../common/http.filter";
import { LedgerService, KIND_LABEL, LedgerRow } from "./ledger.service";
import { WalletService } from "./wallet.service";

class PageDto {
  @Type(() => Number) @IsInt() @Min(1) page = 1;
  @Type(() => Number) @IsInt() @Min(1) @Max(100) pageSize = 20;
}

@Controller()
export class BillingController {
  constructor(
    private wallet: WalletService,
    private ledger: LedgerService,
  ) {}

  @Get("balance")
  async balance(@CurrentUser() userId: number) {
    const w = await this.wallet.get(userId);
    return R({
      balance_cents: Number(w.balance_cents),
      gift_cents: Number(w.gift_cents),
      total_cents: Number(w.balance_cents) + Number(w.gift_cents),
    });
  }

  @Get("balance/transactions")
  async transactions(@CurrentUser() userId: number, @Query() q: PageDto) {
    const { items, total } = await this.ledger.listByUser(userId, q.page, q.pageSize);
    return R({
      total,
      page: q.page,
      pageSize: q.pageSize,
      items: items.map(toVo),
    });
  }
}

function toVo(r: LedgerRow) {
  return {
    id: r.id,
    kind: r.kind,
    kind_label: KIND_LABEL[r.kind] ?? `kind${r.kind}`,
    model: r.model,
    tokens_in: r.tokens_in,
    tokens_out: r.tokens_out,
    amount_cents: Number(r.amount_cents),
    task_id: r.task_id,
    created_at: r.created_at,
  };
}
