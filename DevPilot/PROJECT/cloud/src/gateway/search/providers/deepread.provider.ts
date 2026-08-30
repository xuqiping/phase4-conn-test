// deep_read：网页转 Markdown。真链路 Jina（env 配 key）；mock 默认。
import { Injectable } from "@nestjs/common";
import { DeepReadProvider } from "../search.types";

@Injectable()
export class MockDeepRead implements DeepReadProvider {
  readonly name = "mock-jina";
  enabled() { return process.env.SEARCH_MOCK !== "false"; }
  async read(url: string) {
    if (url.startsWith("https://dead.example")) throw new Error("dead");
    return `# ${url}\n\n这是 mock 转出的 Markdown 正文。`;
  }
}

@Injectable()
export class JinaDeepRead implements DeepReadProvider {
  readonly name = "jina";
  enabled() { return Boolean(process.env.JINA_API_KEY); }
  async read(url: string): Promise<string> {
    try {
      const resp = await fetch(`https://r.jina.ai/${url}`, {
        headers: { authorization: `Bearer ${process.env.JINA_API_KEY}` },
      });
      if (!resp.ok) throw new Error();
      return await resp.text();
    } catch {
      throw new Error("deep read failed");
    }
  }
}
