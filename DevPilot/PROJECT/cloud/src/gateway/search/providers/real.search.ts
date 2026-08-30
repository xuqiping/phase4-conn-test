// 真实搜索供应商：博查（env 配了 key 才启用）+ SearXNG 兜底。
// 联调待运营侧配 key；本地/CI 走 mock，不烧钱。
import { Injectable } from "@nestjs/common";
import { SearchProvider, SearchResult, SearchUpstreamError } from "../search.types";

@Injectable()
export class BochaSearch implements SearchProvider {
  readonly name = "bocha";
  enabled() { return Boolean(process.env.BOCHA_API_KEY); }
  async search(query: string): Promise<SearchResult[]> {
    try {
      const resp = await fetch("https://api.bochaai.com/v1/web-search", {
        method: "POST",
        headers: {
          "content-type": "application/json",
          authorization: `Bearer ${process.env.BOCHA_API_KEY}`,
        },
        body: JSON.stringify({ query, freshness: "noLimit", summary: true, count: 8 }),
      });
      if (!resp.ok) throw new Error();
      const data = (await resp.json()) as {
        pages?: { title?: string; url?: string; summary?: string }[];
      };
      return (data.pages ?? []).map((p) => ({
        title: p.title ?? "", url: p.url ?? "", snippet: p.summary ?? "",
      }));
    } catch {
      throw new SearchUpstreamError();
    }
  }
}

@Injectable()
export class SearxngSearch implements SearchProvider {
  readonly name = "searxng";
  enabled() { return Boolean(process.env.SEARXNG_URL); }
  async search(query: string): Promise<SearchResult[]> {
    try {
      const resp = await fetch(
        `${process.env.SEARXNG_URL}/search?q=${encodeURIComponent(query)}&format=json`,
      );
      if (!resp.ok) throw new Error();
      const data = (await resp.json()) as {
        results?: { title?: string; url?: string; content?: string }[];
      };
      return (data.results ?? []).slice(0, 8).map((r) => ({
        title: r.title ?? "", url: r.url ?? "", snippet: r.content ?? "",
      }));
    } catch {
      throw new SearchUpstreamError();
    }
  }
}
