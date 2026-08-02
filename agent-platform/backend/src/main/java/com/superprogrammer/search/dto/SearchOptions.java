package com.superprogrammer.search.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Value;

/**
 * 搜索调用参数。value-object 不可变：由 WebSearchService 按 system_settings 默认值组装后传入 provider。
 *
 * 字段说明：
 * - maxResults：最多返回结果数（top N，建议 ≤10；正文抽取越多数越慢）。
 * - fetchContent：是否抓全文正文；false 只回 snippet（外部供应商通常自带 content，BuiltIn 自抓可控）。
 * - timeoutMs：单次搜索整体超时（含外部 API 或 SearXNG+并发抓取），由 service 层统一兜底，provider 内部可更短。
 */
@Value
@Builder
@AllArgsConstructor
public class SearchOptions {
    @Builder.Default
    Integer maxResults = 5;

    @Builder.Default
    Boolean fetchContent = true;

    @Builder.Default
    Integer timeoutMs = 10000;
}
