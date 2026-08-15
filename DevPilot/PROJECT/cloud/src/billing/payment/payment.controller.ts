// 充值接口：下单（需登录）。回调见 webhook.controller（@Public，靠 HMAC 验签）。
import { Body, Controller, Post, HttpCode } from "@nestjs/common";
import { IsIn } from "class-validator";
import { CurrentUser } from "../../auth/jwt.guard";
import { R } from "../../common/http.filter";
import { PaymentService, PACKS } from "./payment.service";

class RechargeDto {
  @IsIn(PACKS.map((p) => p.code), { message: "充值档位不正确" }) pack_code!: string;
}

@Controller("payments")
export class PaymentController {
  constructor(private payment: PaymentService) {}

  @Post("recharge")
  @HttpCode(200)
  recharge(@CurrentUser() userId: number, @Body() dto: RechargeDto) {
    return this.payment.createOrder(userId, dto.pack_code).then(R);
  }
}
