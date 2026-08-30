import { Module } from "@nestjs/common";
import { BillingModule } from "../billing/billing.module";
import { KvModule } from "../common/kv.module";
import { EstimateController } from "./estimate.controller";
import { ChatController } from "./chat.controller";
import { MeterService } from "./meter.service";
import { ProviderRegistry } from "./providers";
import { MockProvider } from "./providers/mock.provider";
import { AnthropicProvider } from "./providers/anthropic.provider";
import { SearchController } from "./search/search.controller";
import { SearchService } from "./search/search.service";
import { MockPrimarySearch, MockBackupSearch } from "./search/providers/mock.search";
import { BochaSearch, SearxngSearch } from "./search/providers/real.search";
import { MockDeepRead, JinaDeepRead } from "./search/providers/deepread.provider";

@Module({
  imports: [BillingModule, KvModule],
  controllers: [EstimateController, ChatController, SearchController],
  providers: [
    MeterService, ProviderRegistry, MockProvider, AnthropicProvider,
    SearchService, MockPrimarySearch, MockBackupSearch, BochaSearch, SearxngSearch,
    MockDeepRead, JinaDeepRead,
  ],
})
export class GatewayModule {}
