// Anthropic 供应商：api_identities 取 key（AES-GCM 解密，绝不落日志），SSE 解析成统一 StreamChunk。
// 真实上游联调待运营侧配 key 后验证（本地/CI 一律走 mock，不烧钱）。
import { Injectable } from "@nestjs/common";
import { DbService } from "../../db/db.service";
import { decryptSecret } from "../../common/crypto";
import { ChatProvider, ChatRequest, StreamChunk, UpstreamError } from "../provider.interface";

@Injectable()
export class AnthropicProvider implements ChatProvider {
  readonly name = "anthropic";

  constructor(private db: DbService) {}

  private async apiKey(): Promise<string> {
    const res = await this.db.query<{ api_key_encrypted: string }>(
      `SELECT api_key_encrypted FROM api_identities
       WHERE provider = 'anthropic' AND status = 1 ORDER BY priority LIMIT 1`,
    );
    const row = res.rows[0];
    if (!row) throw new UpstreamError("模型供应商未配置");
    try {
      return decryptSecret(row.api_key_encrypted);
    } catch {
      throw new UpstreamError("模型密钥配置错误，请联系运营");
    }
  }

  async *chatStream(req: ChatRequest): AsyncIterable<StreamChunk> {
    const key = await this.apiKey();
    let resp: Response;
    try {
      resp = await fetch("https://api.anthropic.com/v1/messages", {
        method: "POST",
        headers: {
          "content-type": "application/json",
          "x-api-key": key,
          "anthropic-version": "2023-06-01",
        },
        body: JSON.stringify({
          model: req.model,
          max_tokens: req.max_tokens ?? 4096,
          stream: true,
          messages: req.messages,
        }),
      });
    } catch {
      throw new UpstreamError();
    }
    if (!resp.ok || !resp.body) throw new UpstreamError();

    // SSE 逐行解析：content_block_delta 取文本，message_delta 取权威 usage
    const reader = resp.body.getReader();
    const decoder = new TextDecoder();
    let buf = "";
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      buf += decoder.decode(value, { stream: true });
      const lines = buf.split("\n");
      buf = lines.pop() ?? "";
      for (const line of lines) {
        if (!line.startsWith("data:")) continue;
        const payload = line.slice(5).trim();
        if (payload === "[DONE]") return;
        let evt: Record<string, unknown>;
        try {
          evt = JSON.parse(payload);
        } catch {
          continue;
        }
        if (evt.type === "content_block_delta") {
          const text = (evt.delta as { text?: string })?.text;
          if (text) yield { type: "delta", text };
        } else if (evt.type === "message_delta") {
          const usage = evt.usage as { input_tokens?: number; output_tokens?: number };
          if (usage) {
            yield {
              type: "usage",
              usage: { input_tokens: usage.input_tokens ?? 0, output_tokens: usage.output_tokens ?? 0 },
            };
          }
        }
      }
    }
  }
}
