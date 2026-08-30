// 搜索网关类型：供应商统一返回 SearchResult；降级链由 SearchService 编排。
import { HttpException, HttpStatus } from "@nestjs/common";

export interface SearchResult {
  title: string;
  url: string;
  snippet: string;
}

/** 全链挂：502 语义（plan：主备全挂→502） */
export class SearchUpstreamError extends HttpException {
  constructor(message = "搜索服务暂不可用，请稍后重试") {
    super(message, HttpStatus.BAD_GATEWAY);
  }
}

/** 供应商接口：enabled()=环境变量配了才启用（配置开关，运维清单） */
export interface SearchProvider {
  readonly name: string;
  enabled(): boolean;
  search(query: string): Promise<SearchResult[]>;
}

export interface DeepReadProvider {
  readonly name: string;
  enabled(): boolean;
  read(url: string): Promise<string>; // Markdown
}
