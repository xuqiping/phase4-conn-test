// Mock 上游：e2e / 本地联调专用，零成本、行为确定。
// usage 固定 1M in / 0.5M out（配合单价表 = 315 分，测试可精确断言入账金额）。
import { Injectable } from "@nestjs/common";
import { ChatProvider, ChatRequest, StreamChunk, UpstreamError } from "../provider.interface";

@Injectable()
export class MockProvider implements ChatProvider {
  readonly name = "mock";

  async *chatStream(req: ChatRequest): AsyncIterable<StreamChunk> {
    if (req.messages.at(-1)?.content.startsWith("[dead]")) {
      throw new UpstreamError(); // e2e：模拟上游挂 → 网关 502 语义
    }
    const reply = `收到：${req.messages.at(-1)?.content ?? ""}`;
    for (const word of reply.split("")) {
      yield { type: "delta", text: word };
    }
    yield { type: "usage", usage: { input_tokens: 1_000_000, output_tokens: 500_000 } };
  }
}
