import { Module } from "@nestjs/common";
import { BillingModule } from "../billing/billing.module";
import { EstimateController } from "./estimate.controller";

@Module({
  imports: [BillingModule],
  controllers: [EstimateController],
})
export class GatewayModule {}
