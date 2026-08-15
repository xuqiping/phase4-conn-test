// 上游模型供应商适配层：网关只认这个接口，换/加供应商不动 chat 主链。
// 优先 anthropic；OpenAI 预留（P03+）。e2e/本地默认 mock（不烧真钱）。
export interface ChatRequest {
  model: string;
  messages: { role: "user" | "assistant"; content: string }[];
  max_tokens?: number;
}

/** 流式块：文本增量，或末帧权威 usage（上游报多少就记多少） */
export interface StreamChunk {
  type: "delta" | "usage";
  text?: string;
  usage?: { input_tokens: number; output_tokens: number };
}

export interface ChatProvider {
  readonly name: string;
  /** 流式对话。挂了抛 UpstreamError（网关统一映射 502）。 */
  chatStream(req: ChatRequest): AsyncIterable<StreamChunk>;
}

/** 上游不可用/返回异常——网关层映射 502，绝不透传上游原文（可能含敏感信息） */
export class UpstreamError extends Error {
  constructor(message = "上游模型服务不可用，请稍后重试") {
    super(message);
    this.name = "UpstreamError";
  }
}
