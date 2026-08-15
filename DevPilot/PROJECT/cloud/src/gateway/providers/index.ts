// 供应商注册表：环境变量 GATEWAY_PROVIDER 切换（dev/test 默认 mock，生产 anthropic）。
import { Injectable } from "@nestjs/common";
import { ChatProvider } from "../provider.interface";
import { MockProvider } from "./mock.provider";
import { AnthropicProvider } from "./anthropic.provider";

@Injectable()
export class ProviderRegistry {
  constructor(
    private mock: MockProvider,
    private anthropic: AnthropicProvider,
  ) {}

  resolve(): ChatProvider {
    switch (process.env.GATEWAY_PROVIDER ?? "mock") {
      case "anthropic":
        return this.anthropic;
      case "mock":
      default:
        return this.mock;
    }
  }
}
