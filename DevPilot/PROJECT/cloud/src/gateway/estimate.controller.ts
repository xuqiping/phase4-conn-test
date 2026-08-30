// 网关·预估接口：任务发起前按单价表算 cost_cents_est（plan：单价配置不进库）。
import { Body, Controller, HttpCode, Post } from "@nestjs/common";
import { IsInt, IsString, Min, MinLength } from "class-validator";
import { Type } from "class-transformer";
import { CurrentUser } from "../auth/jwt.guard";
import { R } from "../common/http.filter";
import { estimateCostCents } from "../billing/pricing";
import { WalletService } from "../billing/wallet.service";

class EstimateDto {
  @IsString() @MinLength(1) model: string;
  @Type(() => Number) @IsInt() @Min(0) input_tokens: number;
  @Type(() => Number) @IsInt() @Min(0) output_tokens: number;
}

@Controller("gateway")
export class EstimateController {
  constructor(private wallet: WalletService) {}

  /** 返回预估费用 + 当前可用余额，前端据此提示「余额是否够跑这一单」。 */
  @Post("estimate")
  @HttpCode(200)
  async estimate(@CurrentUser() userId: number, @Body() dto: EstimateDto) {
    const cost = estimateCostCents(dto.model, dto.input_tokens, dto.output_tokens);
    const w = await this.wallet.get(userId);
    const total = Number(w.balance_cents) + Number(w.gift_cents);
    return R({ cost_cents_est: cost, balance_total_cents: total, sufficient: total >= cost });
  }
}
