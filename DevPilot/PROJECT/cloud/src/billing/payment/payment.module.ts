import { Module } from "@nestjs/common";
import { BillingModule } from "../billing.module";
import { PaymentController } from "./payment.controller";
import { WebhookController } from "./webhook.controller";
import { PaymentService } from "./payment.service";

@Module({
  controllers: [PaymentController, WebhookController],
  imports: [BillingModule],
  providers: [PaymentService],
})
export class PaymentModule {}
