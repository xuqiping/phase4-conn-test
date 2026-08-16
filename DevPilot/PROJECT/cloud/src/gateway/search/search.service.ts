// 搜索服务：降级链（主家挂自动降级、响应带标记）+ 计次计费 + 缓存防重复扣费。
// 计费联动：缓存命中不产生账目、不扣钱（plan 联动点 3 的「半选态」）。
import { HttpException, HttpStatus, Injectable } from "@nestjs/common";
import { createHash } from "node:crypto";
import { BillingService } from "../../billing/billing.service";
import { WalletService } from "../../billing/wallet.service";
import { KvService } from "../../common/kv.service";
import { SearchResult, SearchProvider, DeepReadProvider, SearchUpstreamError } from "./search.types";
import { MockPrimarySearch, MockBackupSearch } from "./providers/mock.search";
import { BochaSearch, SearxngSearch } from "./providers/real.search";
import { MockDeepRead, JinaDeepRead } from "./providers/deepread.provider";

const CACHE_TTL_SEC = Number(process.env.SEARCH_CACHE_TTL_SEC ?? 600);
const WEB_COST_CENTS = Number(process.env.SEARCH_COST_CENTS ?? 10);
const DEEPREAD_COST_CENTS = Number(process.env.DEEPREAD_COST_CENTS ?? 20);

@Injectable()
export class SearchService {
  // 链序：模型原生搜索（P04 接任务链时接）→ 博查 → SearXNG；mock 模式替换为 mock 主/备
  private chain: SearchProvider[];
  private readers: DeepReadProvider[];

  constructor(
    private billing: BillingService,
    private wallet: WalletService,
    private kv: KvService,
    mockPrimary: MockPrimarySearch,
    mockBackup: MockBackupSearch,
    bocha: BochaSearch,
    searxng: SearxngSearch,
    mockReader: MockDeepRead,
    jina: JinaDeepRead,
  ) {
    const real = [bocha, searxng];
    this.chain = [
      mockPrimary, // SEARCH_MOCK=false 时 enabled()=false 自动跳过
      ...real,
      mockBackup, // mock 兜底；真环境里 searxng 才是兜底
    ];
    this.readers = [jina, mockReader];
  }

  private activeChain(): SearchProvider[] {
    return this.chain.filter((p) => p.enabled());
  }

  async web(userId: number, query: string, nonce: string) {
    const cacheKey = `search:v1:${hash(query)}`;
    const cached = await this.kv.get(cacheKey);
    if (cached) return { ...JSON.parse(cached), cached: true };

    await this.precheck(userId, WEB_COST_CENTS);
    const { results, provider, degraded } = await this.runChain(query);
    await this.charge(userId, query, nonce, WEB_COST_CENTS);

    const payload = { results, provider, degraded, cached: false };
    await this.kv.set(cacheKey, JSON.stringify({ results, provider, degraded }), CACHE_TTL_SEC);
    return payload;
  }

  async deepRead(userId: number, url: string, nonce: string) {
    const cacheKey = `deepread:v1:${hash(url)}`;
    const cached = await this.kv.get(cacheKey);
    if (cached) return { markdown: JSON.parse(cached).markdown, provider: JSON.parse(cached).provider, cached: true };

    await this.precheck(userId, DEEPREAD_COST_CENTS);
    let markdown: string | null = null;
    let provider = "";
    for (const r of this.readers) {
      if (!r.enabled()) continue;
      try {
        markdown = await r.read(url);
        provider = r.name;
        break;
      } catch {
        // 降级到下一家
      }
    }
    if (markdown === null) throw new SearchUpstreamError();
    await this.charge(userId, url, nonce, DEEPREAD_COST_CENTS);
    await this.kv.set(cacheKey, JSON.stringify({ markdown, provider }), CACHE_TTL_SEC);
    return { markdown, provider, cached: false };
  }

  private async runChain(query: string): Promise<{ results: SearchResult[]; provider: string; degraded: boolean }> {
    const active = this.activeChain();
    if (active.length === 0) throw new SearchUpstreamError();
    let firstError: unknown = null;
    for (let i = 0; i < active.length; i++) {
      try {
        const results = await active[i].search(query);
        if (results.length === 0) throw new SearchUpstreamError("空结果按失败处理");
        return { results, provider: active[i].name, degraded: i > 0 }; // 非首家命中=降级
      } catch (e) {
        firstError ??= e;
      }
    }
    // eslint-disable-next-line no-console
    console.error(`[search-fallback-exhausted] query=${query.slice(0, 50)}`, firstError);
    throw new SearchUpstreamError();
  }

  private async precheck(userId: number, costCents: number) {
    const w = await this.wallet.get(userId);
    if (Number(w.balance_cents) + Number(w.gift_cents) < costCents) {
      throw new HttpException("余额不足，请充值后再试", HttpStatus.PAYMENT_REQUIRED);
    }
  }

  /** 计次入账：kind=1、model='web_search'——明细里单列「联网搜索」 */
  private async charge(userId: number, query: string, nonce: string, amountCents: number) {
    await this.billing.charge({
      userId,
      amountCents,
      model: "web_search",
      taskId: nonce,
      nonce: `search:${nonce}`,
    });
  }
}

function hash(s: string): string {
  return createHash("sha1").update(s.trim().toLowerCase()).digest("hex").slice(0, 24);
}
