package com.superprogrammer.search.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.search.dto.SearchOptions;
import com.superprogrammer.search.dto.SearchResult;
import com.superprogrammer.system.service.SystemSettingService;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Tavily 外部搜索供应商。POST https://api.tavily.com/search，body 带 api_key。
 *
 * - {@link #getName()} 返 "tavily"，对应 system_settings search.active_provider=tavily。
 * - {@link #available()} = Tavily key 已配置（AES 解密后非空）。
 * - {@link #search(String, SearchOptions)}：POST → 解析 results[]{title,url,content,score}；
 *   任何失败返回空列表（service 层降级 BuiltIn），不抛异常——符合 {@link WebSearchProvider} 契约。
 *
 * 安全：key 从 system_settings AES 解密读，不回显明文（写由后台 Step7 接）。错误响应体不进日志（防泄漏）。
 * 测试：{@link #setEndpoint(String)} 把端点换成 MockWebServer。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TavilyProvider implements WebSearchProvider {

    private static final String DEFAULT_ENDPOINT = "https://api.tavily.com/search";

    private final WebClient.Builder webClientBuilder;
    private final SystemSettingService settingService;

    /** 可注入端点（测 MockWebServer）。 */
    @Setter
    private String endpoint = DEFAULT_ENDPOINT;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String getName() {
        return "tavily";
    }

    @Override
    public boolean available() {
        String key = settingService.getSearchApiKey("tavily");
        return key != null && !key.isBlank();
    }

    @Override
    public List<SearchResult> search(String query, SearchOptions opts) {
        String apiKey = settingService.getSearchApiKey("tavily");
        if (apiKey == null || apiKey.isBlank()) {
            return List.of();
        }
        int max = opts == null || opts.getMaxResults() == null ? 5 : opts.getMaxResults();
        long timeoutMs = opts == null || opts.getTimeoutMs() == null ? 10000 : opts.getTimeoutMs();

        String body = "{\"query\":\"" + jsonEscape(query) + "\""
                + ",\"api_key\":\"" + jsonEscape(apiKey) + "\""
                + ",\"max_results\":" + max
                + ",\"search_depth\":\"basic\"}";

        try {
            String json = webClientBuilder.build().post()
                    .uri(endpoint)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofMillis(timeoutMs));
            return parse(json, max);
        } catch (Exception e) {
            log.warn("Tavily 查询失败，返回空: err={}", e.getMessage());
            return List.of();
        }
    }

    /** 解析 Tavily results[]。失败 → 空列表。 */
    private List<SearchResult> parse(String json, int max) {
        List<SearchResult> results = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return results;
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode arr = root.path("results");
            if (!arr.isArray()) {
                return results;
            }
            for (JsonNode r : arr) {
                if (results.size() >= max) {
                    break;
                }
                String url = r.path("url").asText("");
                if (url.isBlank()) {
                    continue;
                }
                Double score = r.has("score") && r.get("score").isNumber() ? r.get("score").asDouble() : null;
                results.add(SearchResult.builder()
                        .title(r.path("title").asText(""))
                        .url(url)
                        .snippet(r.path("content").asText(""))
                        .content(r.path("content").asText(""))
                        .score(score)
                        .build());
            }
        } catch (Exception e) {
            log.warn("Tavily JSON 解析失败: {}", e.getMessage());
        }
        return results;
    }

    private static String jsonEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
