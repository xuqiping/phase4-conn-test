package com.superprogrammer.knowledge.connector;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WP6 Step2：URL 站点连接器——mock 源站全链路爬取：同域过滤/白名单后缀/已访去重（A→B→A 环）
 * /深度 ≤2/页数闸。guard 放行（源站即 127.0.0.1，SSRF 语义由 SafeHttpFetchTest 直测）。
 */
class UrlSiteConnectorTest {

    private HttpServer server;
    private String origin;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String html = page(exchange.getRequestURI().getPath());
            byte[] body = html.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.getResponseHeaders().add("ETag", "\"etag-" + exchange.getRequestURI().getPath() + "\"");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        origin = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private String page(String path) {
        return switch (path) {
            case "/" -> """
                    <html><body>
                    <a href="/a.html">A（回链首页+链 B——成环）</a>
                    <a href="/doc.pdf">白名单文档</a>
                    <a href="/img.png">非白名单</a>
                    <a href="http://example.com/x.html">出域</a>
                    </body></html>""";
            case "/a.html" -> """
                    <html><body>
                    <a href="/">回链首页（去重）</a>
                    <a href="/b.html">B</a>
                    <a href="/doc.pdf">重复发现（去重）</a>
                    </body></html>""";
            case "/b.html" -> """
                    <html><body>
                    <a href="/a.html">回链 A（去重）</a>
                    <a href="/sub/c.html">深度 2</a>
                    <a href="/sub/deep/d.html">深度 3（超深不展开链接，但本身已在 depth2 页发现——应作为条目出现）</a>
                    </body></html>""";
            default -> "<html><body>empty</body></html>";
        };
    }

    private UrlSiteConnector connector(int maxPages) {
        Predicate<String> allowAll = url -> true;
        return new UrlSiteConnector(Map.of("seedUrl", origin + "/"),
                allowAll, new FetchLimiter(0, Long.MAX_VALUE), maxPages, 2);
    }

    @Test
    void crawl_sameHostDedupWhitelistDepth() throws Exception {
        List<KnowledgeConnectorSpi.ExternalDoc> docs = connector(50).list();

        List<String> ids = docs.stream().map(KnowledgeConnectorSpi.ExternalDoc::externalId).toList();
        assertTrue(ids.contains(origin + "/"), "种子页应入列");
        assertTrue(ids.contains(origin + "/a.html"));
        assertTrue(ids.contains(origin + "/b.html"));
        assertTrue(ids.contains(origin + "/doc.pdf"));
        assertTrue(ids.contains(origin + "/sub/c.html"), "深度 2 页面应入列");
        assertTrue(ids.contains(origin + "/sub/deep/d.html"), "深度 3 条目在深度 2 页被发现，应作为文档入列（只是不再展开其链接）");
        assertFalse(ids.stream().anyMatch(id -> id.contains("example.com")), "出域链接不得入列");
        assertFalse(ids.stream().anyMatch(id -> id.endsWith(".png")), "非白名单后缀不得入列");
        // 已访去重：每个 URL 恰一条（A↔B↔首页环 + doc.pdf 双路发现）
        assertEquals(ids.size(), ids.stream().distinct().count(), "externalId 必须全局去重");
        // 直接抓取页（首页）带 ETag；链接发现页 etag=null（未抓取，首轮全量下载后由账本记账）
        KnowledgeConnectorSpi.ExternalDoc seedDoc = docs.stream()
                .filter(d -> d.externalId().equals(origin + "/")).findFirst().orElseThrow();
        assertTrue(seedDoc.etag() != null && seedDoc.etag().contains("etag-"), "直接抓取页应有 ETag 指纹");
    }

    @Test
    void pageCap_stopsCrawl() throws Exception {
        List<KnowledgeConnectorSpi.ExternalDoc> docs = connector(2).list();
        assertEquals(2, docs.size(), "页数闸到顶即停");
    }

    @Test
    void fetch_returnsBytesThroughGuardAndLimiter() throws Exception {
        UrlSiteConnector connector = connector(50);
        byte[] body = connector.fetch(new KnowledgeConnectorSpi.ExternalDoc(
                origin + "/doc.pdf", null, "doc.pdf"));
        assertTrue(body.length > 0, "fetch 应返回响应体字节");
    }
}
