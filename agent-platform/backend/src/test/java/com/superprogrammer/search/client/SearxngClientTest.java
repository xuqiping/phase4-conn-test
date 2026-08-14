package com.superprogrammer.search.client;

import com.superprogrammer.search.util.ContentExtractor;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SearxngClientTest {

    private MockWebServer server;
    private SearxngClient client;

    @BeforeEach
    void setup() throws IOException {
        server = new MockWebServer();
        server.start();
        String base = server.url("/").toString().replaceAll("/$", "");
        client = new SearxngClient(WebClient.builder(), new ContentExtractor(),
                new com.superprogrammer.common.metrics.BizMetrics(
                        new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
        client.setBaseUrl(base);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    @DisplayName("未配置 base_url → isConfigured=false，search 返空")
    void not_configured_returns_empty() {
        SearxngClient unconfigured = new SearxngClient(WebClient.builder(), new ContentExtractor(),
                new com.superprogrammer.common.metrics.BizMetrics(
                        new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
        unconfigured.setBaseUrl("");
        assertThat(unconfigured.isConfigured()).isFalse();
        assertThat(unconfigured.search("anything", 5)).isEmpty();
    }

    @Test
    @DisplayName("SearXNG JSON 解析：取前 maxResults 条 title/url/snippet")
    void search_parses_results() throws InterruptedException {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"results":[
                          {"title":"结果一","url":"https://a.example.com/1","content":"摘要一"},
                          {"title":"结果二","url":"https://b.example.com/2","content":"摘要二"},
                          {"title":"无URL项应跳过","url":"","content":"x"}
                        ]}
                        """));
        List<SearxngClient.SearxngItem> items = client.search("测试 query", 5);

        assertThat(items).hasSize(2);
        assertThat(items.get(0).title()).isEqualTo("结果一");
        assertThat(items.get(0).url()).isEqualTo("https://a.example.com/1");
        assertThat(items.get(0).snippet()).isEqualTo("摘要一");

        // query 走 URL 编码
        RecordedRequest req = server.takeRequest();
        assertThat(req.getPath()).contains("q=").contains("format=json").contains("categories=general");
    }

    @Test
    @DisplayName("maxResults 截断：返回不超过上限")
    void search_truncates_to_max() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"results":[
                          {"title":"a","url":"https://a.example.com/1","content":""},
                          {"title":"b","url":"https://b.example.com/2","content":""},
                          {"title":"c","url":"https://c.example.com/3","content":""}
                        ]}
                        """));
        List<SearxngClient.SearxngItem> items = client.search("q", 2);
        assertThat(items).hasSize(2);
    }

    @Test
    @DisplayName("SearXNG 返非 JSON / 5xx → 返空不抛")
    void search_failure_returns_empty() {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("err"));
        assertThat(client.search("q", 5)).isEmpty();

        server.enqueue(new MockResponse().setBody("not json"));
        assertThat(client.search("q", 5)).isEmpty();
    }

    @Test
    @DisplayName("fetchContent：SSRF 拒内网段 → 返空")
    void fetchContent_ssrf_rejected() {
        assertThat(client.fetchContent("http://127.0.0.1/secret")).isEmpty();
        assertThat(client.fetchContent("http://10.0.0.5/x")).isEmpty();
    }

    // ---- 安全体系 S5 · SEC-FR-082（H SSRF 重定向收口）----

    @Test
    @DisplayName("fetchContent：SSRF 拒 CGNAT/IPv6-ULA（对齐 SsrfGuard 段表）→ 返空")
    void fetchContent_ssrf_cgnatAndUla_rejected() {
        assertThat(client.fetchContent("http://100.64.1.1/x")).isEmpty();     // CGNAT 100.64/10
        assertThat(client.fetchContent("http://[fd12::1]/x")).isEmpty();     // IPv6 ULA fd00::/8
    }

    @Test
    @DisplayName("重定向判定：3xx 才跟随")
    void redirect_status_detection() {
        assertThat(SearxngClient.isRedirect(301)).isTrue();
        assertThat(SearxngClient.isRedirect(302)).isTrue();
        assertThat(SearxngClient.isRedirect(307)).isTrue();
        assertThat(SearxngClient.isRedirect(200)).isFalse();
        assertThat(SearxngClient.isRedirect(404)).isFalse();
    }

    @Test
    @DisplayName("重定向目标解析：相对 Location 基于当前 URL 转绝对")
    void redirect_location_resolution() {
        // 相对路径
        assertThat(SearxngClient.resolveLocation("https://a.example.com/dir/page", "next"))
                .isEqualTo("https://a.example.com/dir/next");
        // 绝对路径
        assertThat(SearxngClient.resolveLocation("https://a.example.com/dir/page", "/root"))
                .isEqualTo("https://a.example.com/root");
        // 跨站绝对 URL（后续会过 assertPublicUrl，内网目标在此被拒）
        assertThat(SearxngClient.resolveLocation("https://a.example.com/dir/page", "http://169.254.169.254/meta"))
                .isEqualTo("http://169.254.169.254/meta");
    }
}
