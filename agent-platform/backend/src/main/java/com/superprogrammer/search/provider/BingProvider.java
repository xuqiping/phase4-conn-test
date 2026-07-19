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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Bing Web Search 外部供应商。GET https://api.bing.microsoft.com/v7.0/search，header Ocp-Apim-Subscription-Key。
 *
 * - {@link #getName()} 返 "bing"。
 * - {@link #available()} = Bing key 已配置。
 * - {@link #search(String, SearchOptions)}：GET ?q=&count= → 解析 webPages.value[]{name,url,snippet}；
 *   content 留空（外部 provider 不自抓）。
 *
 * 失败返回空列表（降级 BuiltIn），不抛异常。测试：{@link #setEndpoint(String)} 换 MockWebServer。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BingProvider implements WebSearchProvider {

    private static final String DEFAULT_ENDPOINT = "https://api.bing.microsoft.com/v7.0/search";

    private final WebClient.Builder webClientBuilder;
    private final SystemSettingService settingService;

    /** 可注入端点（测 MockWebServer）。 */
    @Setter
    private String endpoint = DEFAULT_ENDPOINT;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String getName() {
        return "bing";
    }

    @Override
    public boolean available() {
        String key = settingService.getSearchApiKey("bing");
        return key != null && !key.isBlank();
    }

    @Override
    public List<SearchResult> search(String query, SearchOptions opts) {
        String apiKey = settingService.getSearchApiKey("bing");
        if (apiKey == null || apiKey.isBlank()) {
            return List.of();
        }
        int max = opts == null || opts.getMaxResults() == null ? 5 : opts.getMaxResults();
        long timeoutMs = opts == null || opts.getTimeoutMs() == null ? 10000 : opts.getTimeoutMs();

        try {
            String url = endpoint + (endpoint.contains("?") ? "&" : "?")
                    + "q=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                    + "&count=" + max;
            String json = webClientBuilder.build().get()
                    .uri(url)
                    .header("Ocp-Apim-Subscription-Key", apiKey)
                    .header("Accept", "application/json")
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofMillis(timeoutMs));
            return parse(json, max);
        } catch (Exception e) {
            log.warn("Bing 查询失败，返回空: err={}", e.getMessage());
            return List.of();
        }
    }

    /** 解析 Bing webPages.value[]。失败 → 空列表。 */
    private List<SearchResult> parse(String json, int max) {
        List<SearchResult> results = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return results;
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode arr = root.path("webPages").path("value");
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
                results.add(SearchResult.builder()
                        .title(r.path("name").asText(""))
                        .url(url)
                        .snippet(r.path("snippet").asText(""))
                        .content("")
                        .score(null)
                        .build());
            }
        } catch (Exception e) {
            log.warn("Bing JSON 解析失败: {}", e.getMessage());
        }
        return results;
    }
}
