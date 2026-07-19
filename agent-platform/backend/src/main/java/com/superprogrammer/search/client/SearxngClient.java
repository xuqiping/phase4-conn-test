package com.superprogrammer.search.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.superprogrammer.search.util.ContentExtractor;
import com.superprogrammer.search.util.SanitizeUtil;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * SearXNG 元搜索客户端（自建引擎数据源）。
 *
 * 职责：
 * 1. {@link #search(String, int)}：调 SearXNG JSON API（{@code <base>/?q=&format=json&categories=general}）拿 URL 列表。
 * 2. {@link #fetchContent(String)}：对结果 URL 直抓正文（Jsoup + ContentExtractor），抓前过 SSRF 校验。
 *
 * 配置：{@code search.searxng.base-url}（默认空=未部署，available 返 false 走降级）。
 * 测试：{@link #setBaseUrl(String)} 把 base 换成 MockWebServer。
 *
 * 健壮性：所有失败路径返回空/""，不抛异常——provider/service 层据此降级，不让单页故障炸整条搜索。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SearxngClient {

    private final WebClient.Builder webClientBuilder;
    private final ContentExtractor contentExtractor;

    /** SearXNG base URL，如 http://localhost:8888。空表示未部署。 */
    @Value("${search.searxng.base-url:}")
    @Setter
    private String baseUrl;

    /** 直抓单页超时（plan：单页 8s）。 */
    @Value("${search.searxng.fetch-timeout-ms:8000}")
    @Setter
    private int fetchTimeoutMs = 8000;

    /** 直抓 UA 伪装，降低被反爬识别概率。 */
    private static final String USER_AGENT =
            "Mozilla/5.0 (compatible; SuperProgrammerSearchBot/1.0; +https://example.com/bot)";

    /** SearXNG 单条结果（content 即 snippet）。 */
    public record SearxngItem(String title, String url, String snippet) {}

    /** 是否已配置（base 非空）。连通性校验放集成测，本期 available 据此判。 */
    public boolean isConfigured() {
        return baseUrl != null && !baseUrl.isBlank();
    }

    /**
     * 调 SearXNG JSON API 拿结果列表（仅元信息，不抓全文）。
     * 失败/未配置 → 返回空列表。
     */
    public List<SearxngItem> search(String query, int maxResults) {
        if (!isConfigured()) {
            return List.of();
        }
        try {
            String json = fetchJson(query);
            return parseResults(json, maxResults);
        } catch (Exception e) {
            log.warn("SearXNG 查询失败，返回空: query={} err={}", sanitizeQueryForLog(query), e.getMessage());
            return List.of();
        }
    }

    /** 拼 URL 调 SearXNG（query 走 URLEncoder），失败向上抛由 search 统一兜底。 */
    private String fetchJson(String query) {
        String url = baseUrl + (baseUrl.endsWith("/") ? "" : "/")
                + "?q=" + java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8)
                + "&format=json"
                + "&categories=general"
                + "&safesearch=1"
                + "&pageno=1";
        return webClientBuilder.build().get()
                .uri(url)
                .header("User-Agent", USER_AGENT)
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(10));
    }

    /** 解析 SearXNG JSON results[]，取前 maxResults 条（title/url/content）。 */
    private List<SearxngItem> parseResults(String json, int maxResults) {
        List<SearxngItem> items = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return items;
        }
        try {
            JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
            JsonNode results = root.path("results");
            if (!results.isArray()) {
                return items;
            }
            for (JsonNode r : results) {
                if (items.size() >= maxResults) {
                    break;
                }
                String url = r.path("url").asText("");
                String title = r.path("title").asText("");
                String snippet = r.path("content").asText("");
                if (url.isBlank()) {
                    continue;
                }
                items.add(new SearxngItem(title, url, snippet));
            }
        } catch (Exception e) {
            log.warn("SearXNG JSON 解析失败: {}", e.getMessage());
        }
        return items;
    }

    /**
     * 直抓网页正文。抓前 SSRF 校验（拒私有/内网段），失败返回 ""（降级 snippet）。
     */
    public String fetchContent(String url) {
        try {
            SanitizeUtil.assertPublicUrl(url);
            org.jsoup.nodes.Document doc = Jsoup.connect(url)
                    .userAgent(USER_AGENT)
                    .timeout(fetchTimeoutMs)
                    .ignoreContentType(true)
                    .followRedirects(true)
                    .maxBodySize(512 * 1024) // 限 512KB 防 context/内存爆
                    .get();
            return contentExtractor.extract(doc.html(), url);
        } catch (IllegalArgumentException ssrf) {
            // SSRF 命中私有段：记 warn 并拒绝，不抓
            log.warn("SSRF 防护拒绝抓取: {}", ssrf.getMessage());
            return "";
        } catch (Exception e) {
            log.debug("抓取正文失败，降级 snippet: url={} err={}", url, e.getMessage());
            return "";
        }
    }

    /** 审计日志脱敏 query（截断 + 去控制字符），避免日志注入。 */
    private static String sanitizeQueryForLog(String q) {
        return SanitizeUtil.sanitizeText(q, 80);
    }
}
