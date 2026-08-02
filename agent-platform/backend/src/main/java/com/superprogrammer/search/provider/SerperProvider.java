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
 * Serper（Google 搜索 API）外部供应商。POST https://google.serper.dev/search，header X-API-KEY。
 *
 * - {@link #getName()} 返 "serper"。
 * - {@link #available()} = Serper key 已配置。
 * - {@link #search(String, SearchOptions)}：POST body {q,num} → 解析 organic[]{title,link,snippet}；
 *   content 留空（由 WebSearchService 决定是否补抓，本期外部 provider 不自抓）。
 *
 * 失败返回空列表（降级 BuiltIn），不抛异常。测试：{@link #setEndpoint(String)} 换 MockWebServer。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SerperProvider implements WebSearchProvider {

    private static final String DEFAULT_ENDPOINT = "https://google.serper.dev/search";

    private final WebClient.Builder webClientBuilder;
    private final SystemSettingService settingService;

    /** 可注入端点（测 MockWebServer）。 */
    @Setter
    private String endpoint = DEFAULT_ENDPOINT;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String getName() {
        return "serper";
    }

    @Override
    public boolean available() {
        String key = settingService.getSearchApiKey("serper");
        return key != null && !key.isBlank();
    }

    @Override
    public List<SearchResult> search(String query, SearchOptions opts) {
        String apiKey = settingService.getSearchApiKey("serper");
        if (apiKey == null || apiKey.isBlank()) {
            return List.of();
        }
        int max = opts == null || opts.getMaxResults() == null ? 5 : opts.getMaxResults();
        long timeoutMs = opts == null || opts.getTimeoutMs() == null ? 10000 : opts.getTimeoutMs();

        String body = "{\"q\":\"" + jsonEscape(query) + "\",\"num\":" + max + "}";

        try {
            String json = webClientBuilder.build().post()
                    .uri(endpoint)
                    .header("X-API-KEY", apiKey)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofMillis(timeoutMs));
            return parse(json, max);
        } catch (Exception e) {
            log.warn("Serper 查询失败，返回空: err={}", e.getMessage());
            return List.of();
        }
    }

    /** 解析 Serper organic[]。失败 → 空列表。 */
    private List<SearchResult> parse(String json, int max) {
        List<SearchResult> results = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return results;
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode arr = root.path("organic");
            if (!arr.isArray()) {
                return results;
            }
            for (JsonNode r : arr) {
                if (results.size() >= max) {
                    break;
                }
                String url = r.path("link").asText("");
                if (url.isBlank()) {
                    continue;
                }
                results.add(SearchResult.builder()
                        .title(r.path("title").asText(""))
                        .url(url)
                        .snippet(r.path("snippet").asText(""))
                        .content("")
                        .score(null)
                        .build());
            }
        } catch (Exception e) {
            log.warn("Serper JSON 解析失败: {}", e.getMessage());
        }
        return results;
    }

    private static String jsonEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
