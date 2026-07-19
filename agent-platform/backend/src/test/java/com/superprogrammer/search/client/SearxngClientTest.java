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
        client = new SearxngClient(WebClient.builder(), new ContentExtractor());
        client.setBaseUrl(base);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    @DisplayName("未配置 base_url → isConfigured=false，search 返空")
    void not_configured_returns_empty() {
        SearxngClient unconfigured = new SearxngClient(WebClient.builder(), new ContentExtractor());
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
}
