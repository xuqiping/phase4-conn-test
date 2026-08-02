package com.superprogrammer.search.service;

import com.superprogrammer.search.config.SearchConfig;
import com.superprogrammer.search.dto.SearchOptions;
import com.superprogrammer.search.dto.SearchResult;
import com.superprogrammer.search.provider.WebSearchProvider;
import com.superprogrammer.search.util.SanitizeUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 联网搜索统一入口：按 system_settings 的 {@code search.active_provider} 路由 + 失败降级链。
 *
 * 降级链（plan Step4）：
 * <pre>
 *   1. 总开关 search.enabled=false → 直接返空（触发零结果分支，Step5 注入"未检索到"提示）
 *   2. query 校验：去控制字符 + 截断 ≤500（plan 安全项「输入校验」）
 *   3. active provider 非可用（外部无 key） → 降级 builtin + warn
 *   4. 调 active provider（外部重试 1 次）；结果空 + active≠builtin → 降级 builtin
 *   5. builtin 也空 → 返空（Step5 据此走零结果/纯聊天降级）
 * </pre>
 *
 * 审计日志：query(脱敏截断 80)/provider/结果数/耗时ms/traceId —— plan 安全项「审计日志」。
 *
 * 不抛异常：所有失败路径返回空列表，调用方（ChatSessionService Step5）据此降级纯聊天，不阻塞回复。
 *
 * provider 契约：{@link WebSearchProvider#search} 失败返空不抛，故 service 把"空结果"统一当作"可能失败"
 * 触发外部→builtin 降级（真正的零结果极罕见，可接受多一次 builtin 兜底调用）。
 */
@Slf4j
@Service
public class WebSearchService {

    /** query 最大长度（plan 安全项「输入校验」：≤500）。 */
    private static final int MAX_QUERY_LEN = 500;
    /** query 日志脱敏截断长度（防日志膨胀 + 日志注入）。 */
    private static final int LOG_QUERY_LEN = 80;
    /** 外部 provider 失败重试次数（plan：重试 1）。 */
    private static final int EXTERNAL_RETRY = 1;

    private final SearchConfig searchConfig;
    /** provider 名 → 实例（Spring 注入所有 WebSearchProvider bean）。 */
    private final Map<String, WebSearchProvider> providers;
    private final WebSearchProvider builtIn;

    public WebSearchService(SearchConfig searchConfig, List<WebSearchProvider> providerList) {
        this.searchConfig = searchConfig;
        this.providers = new HashMap<>();
        WebSearchProvider builtin = null;
        for (WebSearchProvider p : providerList) {
            providers.put(p.getName(), p);
            if ("builtin".equals(p.getName())) {
                builtin = p;
            }
        }
        this.builtIn = builtin;
    }

    /**
     * 联网搜索主入口。
     *
     * @param query 用户原始查询（会被 sanitize + 截断）
     * @return 结果列表（按相关性排序）；空列表表示禁用/失败/零结果，调用方降级处理
     */
    public List<SearchResult> search(String query) {
        return search(query, searchConfig.defaultOptions());
    }

    public List<SearchResult> search(String query, SearchOptions opts) {
        String traceId = UUID.randomUUID().toString().replace("-", "");
        long start = System.currentTimeMillis();

        // 1. 总开关
        if (!searchConfig.isEnabled()) {
            log.info("[websearch] traceId={} disabled by search.enabled=false, return empty", traceId);
            return List.of();
        }

        // 2. query 校验
        String cleanQuery = sanitizeQuery(query);
        if (cleanQuery.isEmpty()) {
            log.info("[websearch] traceId={} empty query after sanitize, return empty", traceId);
            return List.of();
        }

        // 3. 解析 active provider
        String activeName = searchConfig.activeProvider();
        WebSearchProvider active = providers.get(activeName);
        if (active == null) {
            log.warn("[websearch] traceId={} active provider '{}' not found, fallback builtin", traceId, activeName);
            active = builtIn;
        }

        // 4. active 不可用 → 降级 builtin
        boolean fellToBuiltin = false;
        if (active != null && !active.available()) {
            log.warn("[websearch] traceId={} active provider '{}' unavailable, fallback builtin", traceId, active.getName());
            active = builtIn;
            fellToBuiltin = true;
        }

        if (active == null) {
            log.error("[websearch] traceId={} no provider available (builtin not registered)", traceId);
            return List.of();
        }

        // 5. 调用 active（外部重试 1）
        List<SearchResult> results = callWithRetry(active, cleanQuery, opts, traceId);

        // 6. active 非空但结果空 + active≠builtin + builtin 可用 → 降级 builtin 一次
        if (results.isEmpty() && !fellToBuiltin && !"builtin".equals(active.getName())
                && builtIn != null && builtIn.available()) {
            log.info("[websearch] traceId={} provider '{}' returned empty, fallback builtin", traceId, active.getName());
            results = callWithRetry(builtIn, cleanQuery, opts, traceId);
            fellToBuiltin = true;
        }

        long elapsed = System.currentTimeMillis() - start;
        String usedProvider = fellToBuiltin ? "builtin(fallback)" : active.getName();
        log.info("[websearch] traceId={} query='{}' provider={} results={} elapsed={}ms",
                traceId, maskQuery(cleanQuery), usedProvider, results.size(), elapsed);
        return results;
    }

    /** 调 provider，外部（非 builtin）失败/空重试 1 次。 */
    private List<SearchResult> callWithRetry(WebSearchProvider provider, String query, SearchOptions opts, String traceId) {
        boolean isExternal = !"builtin".equals(provider.getName());
        int attempts = isExternal ? 1 + EXTERNAL_RETRY : 1;
        List<SearchResult> last = List.of();
        for (int i = 0; i < attempts; i++) {
            try {
                last = provider.search(query, opts);
                if (!last.isEmpty()) {
                    return last;
                }
                if (isExternal && i < attempts - 1) {
                    log.debug("[websearch] traceId={} provider '{}' attempt {} empty, retry", traceId, provider.getName(), i + 1);
                }
            } catch (Exception e) {
                // provider 契约不抛，兜底：真抛了也吞掉降级
                log.warn("[websearch] traceId={} provider '{}' threw (contract violation), treat as empty: {}",
                        traceId, provider.getName(), e.getMessage());
                last = List.of();
            }
        }
        return last;
    }

    /** query 清洗：去控制字符 + 截断 ≤500。 */
    private static String sanitizeQuery(String query) {
        if (query == null || query.isBlank()) {
            return "";
        }
        return SanitizeUtil.sanitizeText(query, MAX_QUERY_LEN);
    }

    /** 日志脱敏：截断 80 字符。 */
    private static String maskQuery(String query) {
        return query.length() <= LOG_QUERY_LEN ? query : query.substring(0, LOG_QUERY_LEN) + "...";
    }

    /** 各 provider available() 实时自检（运维配置页展示当前实际可用项；测试连通按钮也读此）。 */
    public Map<String, Boolean> providerAvailability() {
        Map<String, Boolean> map = new HashMap<>();
        for (Map.Entry<String, WebSearchProvider> e : providers.entrySet()) {
            try {
                map.put(e.getKey(), e.getValue().available());
            } catch (Exception ex) {
                map.put(e.getKey(), false);
            }
        }
        return map;
    }
}
