package com.superprogrammer.search.provider;

import com.superprogrammer.search.dto.SearchOptions;
import com.superprogrammer.search.dto.SearchResult;
import com.superprogrammer.system.service.SystemSettingService;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 外部 provider（Tavily/Serper/Bing）单测：MockWebServer 注入响应，验证
 * - available：key 空→false / 非空→true
 * - search：解析正确 + maxResults 截断 + 空 key/HTTP 失败 → 空列表
 * - header/body 透传（X-API-KEY / Ocp-Apim / api_key in body）
 */
class ExternalSearchProviderTest {

    private MockWebServer server;

    @BeforeEach
    void setup() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    private String mockEndpoint() {
        return server.url("/search").toString();
    }

    private SystemSettingService settings(String provider, String key) {
        SystemSettingService s = mock(SystemSettingService.class);
        when(s.getSearchApiKey(provider)).thenReturn(key);
        return s;
    }

    // ============================ Tavily ============================

    @Test
    @DisplayName("Tavily：available key 空→false，非空→true")
    void tavily_available() {
        assertThat(new TavilyProvider(WebClient.builder(), settings("tavily", null)).available()).isFalse();
        assertThat(new TavilyProvider(WebClient.builder(), settings("tavily", "  ")).available()).isFalse();
        assertThat(new TavilyProvider(WebClient.builder(), settings("tavily", "k")).available()).isTrue();
    }

    @Test
    @DisplayName("Tavily：key 空 → 直接返空，不发请求")
    void tavily_no_key_empty() {
        TavilyProvider p = new TavilyProvider(WebClient.builder(), settings("tavily", null));
        assertThat(p.search("q", SearchOptions.builder().build())).isEmpty();
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    @DisplayName("Tavily：POST 解析 results[] + 截断 maxResults + body 带 api_key")
    void tavily_search_parses() throws InterruptedException {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"results\":[{\"title\":\"t1\",\"url\":\"https://a.example/1\",\"content\":\"c1\",\"score\":0.9},"
                        + "{\"title\":\"t2\",\"url\":\"https://b.example/2\",\"content\":\"c2\",\"score\":0.5}]}"));
        TavilyProvider p = new TavilyProvider(WebClient.builder(), settings("tavily", "KEY"));
        p.setEndpoint(mockEndpoint());

        List<SearchResult> r = p.search("hello", SearchOptions.builder().maxResults(1).build());
        assertThat(r).hasSize(1); // maxResults=1 截断
        assertThat(r.get(0).getTitle()).isEqualTo("t1");
        assertThat(r.get(0).getUrl()).isEqualTo("https://a.example/1");
        assertThat(r.get(0).getContent()).isEqualTo("c1");
        assertThat(r.get(0).getScore()).isEqualTo(0.9);

        RecordedRequest req = server.takeRequest();
        assertThat(req.getMethod()).isEqualTo("POST");
        assertThat(req.getBody().readUtf8()).contains("\"api_key\":\"KEY\"").contains("\"query\":\"hello\"");
    }

    @Test
    @DisplayName("Tavily：HTTP 500 → 返空不抛")
    void tavily_http_fail_empty() {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("err"));
        TavilyProvider p = new TavilyProvider(WebClient.builder(), settings("tavily", "KEY"));
        p.setEndpoint(mockEndpoint());
        assertThat(p.search("q", SearchOptions.builder().build())).isEmpty();
    }

    // ============================ Serper ============================

    @Test
    @DisplayName("Serper：available + key 空返空")
    void serper_available_and_empty_key() {
        SerperProvider noKey = new SerperProvider(WebClient.builder(), settings("serper", null));
        assertThat(noKey.available()).isFalse();
        assertThat(noKey.search("q", SearchOptions.builder().build())).isEmpty();
    }

    @Test
    @DisplayName("Serper：POST 解析 organic[] + header X-API-KEY")
    void serper_search_parses() throws InterruptedException {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"organic\":[{\"title\":\"t1\",\"link\":\"https://a.example/1\",\"snippet\":\"s1\"}]}"));
        SerperProvider p = new SerperProvider(WebClient.builder(), settings("serper", "SERPER_KEY"));
        p.setEndpoint(mockEndpoint());

        List<SearchResult> r = p.search("q", SearchOptions.builder().maxResults(5).build());
        assertThat(r).hasSize(1);
        assertThat(r.get(0).getUrl()).isEqualTo("https://a.example/1");
        assertThat(r.get(0).getSnippet()).isEqualTo("s1");
        assertThat(r.get(0).getContent()).isEmpty(); // 外部 provider 不自抓

        RecordedRequest req = server.takeRequest();
        assertThat(req.getMethod()).isEqualTo("POST");
        assertThat(req.getHeader("X-API-KEY")).isEqualTo("SERPER_KEY");
    }

    // ============================ Bing ============================

    @Test
    @DisplayName("Bing：available + key 空返空")
    void bing_available_and_empty_key() {
        BingProvider noKey = new BingProvider(WebClient.builder(), settings("bing", null));
        assertThat(noKey.available()).isFalse();
        assertThat(noKey.search("q", SearchOptions.builder().build())).isEmpty();
    }

    @Test
    @DisplayName("Bing：GET 解析 webPages.value[] + header Ocp-Apim-Subscription-Key + query param")
    void bing_search_parses() throws InterruptedException {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"webPages\":{\"value\":[{\"name\":\"t1\",\"url\":\"https://a.example/1\",\"snippet\":\"s1\"}]}}"));
        BingProvider p = new BingProvider(WebClient.builder(), settings("bing", "BING_KEY"));
        p.setEndpoint(mockEndpoint());

        List<SearchResult> r = p.search("天气", SearchOptions.builder().maxResults(5).build());
        assertThat(r).hasSize(1);
        assertThat(r.get(0).getTitle()).isEqualTo("t1");
        assertThat(r.get(0).getUrl()).isEqualTo("https://a.example/1");

        RecordedRequest req = server.takeRequest();
        assertThat(req.getMethod()).isEqualTo("GET");
        assertThat(req.getHeader("Ocp-Apim-Subscription-Key")).isEqualTo("BING_KEY");
        assertThat(req.getPath()).contains("q=").contains("count=5");
    }
}
