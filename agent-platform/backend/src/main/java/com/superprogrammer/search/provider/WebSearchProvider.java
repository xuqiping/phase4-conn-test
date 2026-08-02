package com.superprogrammer.search.provider;

import com.superprogrammer.search.dto.SearchOptions;
import com.superprogrammer.search.dto.SearchResult;

import java.util.List;

/**
 * 联网搜索供应商统一接口。学 {@code LlmProviderInterface} 范式：
 * 多 provider（Tavily/Serper/Bing/BuiltIn）可插拔，WebSearchService 按 active_provider 路由 + 失败降级。
 *
 * 实现约定：
 * - {@link #getName()} 返回稳定标识（"tavily"/"serper"/"bing"/"builtin"），对应 system_settings 的 search.active_provider 值。
 * - {@link #available()} 自检可用性（外部 provider 校验 key 非空；BuiltIn 校验 searxng.base_url 非空 + 连通）。
 *   service 层据此判降级：active provider 不可用 → 自动降 BuiltIn。
 * - {@link #search(String, SearchOptions)} 返回结果列表；**失败不抛异常**，返回空列表（由 service 统一记审计日志 + 降级）。
 *   query 已由上层做长度/控制字符校验，provider 不重复校验。
 */
public interface WebSearchProvider {

    /** 稳定标识，对应 system_settings 的 search.active_provider。 */
    String getName();

    /**
     * 执行搜索。失败返回空列表，不抛异常（降级由 service 层处理）。
     *
     * @param query 已校验的用户查询（去控制字符、长度 ≤500）
     * @param opts  搜索参数（maxResults / fetchContent / timeoutMs）
     * @return 结果列表，按相关性排序；空列表表示零结果或失败
     */
    List<SearchResult> search(String query, SearchOptions opts);

    /** 自检可用性：外部 provider 校验 key；BuiltIn 校验 SearXNG base_url + 连通。 */
    boolean available();
}
