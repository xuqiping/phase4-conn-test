package com.superprogrammer.search.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Value;

/**
 * 单条联网搜索结果。对标 Tavily results 项：title/url/snippet/content/score。
 * value-object 不可变（readonly）：构造后字段不再改，供 provider 填充后下游只读消费。
 *
 * 字段说明：
 * - title：结果页标题（来自搜索引擎 / SearXNG）。
 * - url：结果页绝对 URL，前端 citation 渲染为可点击外链。
 * - snippet：搜索引擎给的摘要（不抓全文也有）。
 * - content：抓全文后的正文（截断上限由 ContentExtractor 控制，可空）。
 * - score：相关性分（0~1，provider 自定义，仅用于排序提示，不强求精度）。
 */
@Value
@Builder
@AllArgsConstructor
public class SearchResult {
    String title;
    String url;
    String snippet;
    String content;
    Double score;
}
