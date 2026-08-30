// 支付回调（@Public：渠道服务器没有我们的 JWT，靠 HMAC 验签）。
// MVP mock 渠道：签名规则 = HMAC-SHA256(规范化 JSON, PAYMENT_HMAC_SECRET)；
// 真渠道接入时替换验签实现，业务链路（订单 CAS + 幂等入账）不动。
import { BadRequestException, Body, Controller, Headers, HttpCode, Post } from "@nestjs/common";
import { IsInt, IsString, Min, MinLength } from "class-validator";
import { Public } from "../../auth/jwt.guard";
import { R } from "../../common/http.filter";
import { PaymentService } from "./payment.service";

class WebhookDto {
  @IsInt() @Min(1) order_id!: number;
  @IsString() @MinLength(4) trade_no!: string;
  @IsInt() @Min(1) paid_cents!: number;
}

@Controller("payments")
@Public() // 渠道服务器没有我们的 JWT；安全靠 HMAC 验签
export class WebhookController {
  constructor(private payment: PaymentService) {}

  @Post("webhook")
  @HttpCode(200)
  webhook(@Body() dto: WebhookDto, @Headers("x-signature") signature?: string) {
    if (!signature || !this.payment.verify(dto, signature)) {
      // 验签失败=伪造请求，直接 400 且不泄露验签细节
      throw new BadRequestException("回调验签失败");
    }
    // 金额一致性在 service 校验（防改小金额的回调）
    return this.payment.handlePaid(dto.order_id, dto.trade_no, dto.paid_cents).then(R);
  }
}
