import { Module } from "@nestjs/common";
import { BillingModule } from "../billing/billing.module";
import { EstimateController } from "./estimate.controller";
import { ChatController } from "./chat.controller";
import { MeterService } from "./meter.service";
import { ProviderRegistry } from "./providers";
import { MockProvider } from "./providers/mock.provider";
import { AnthropicProvider } from "./providers/anthropic.provider";

@Module({
  imports: [BillingModule],
  controllers: [EstimateController, ChatController],
  providers: [MeterService, ProviderRegistry, MockProvider, AnthropicProvider],
})
export class GatewayModule {}
