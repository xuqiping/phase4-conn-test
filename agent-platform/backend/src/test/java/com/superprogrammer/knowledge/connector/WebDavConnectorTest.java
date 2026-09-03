package com.superprogrammer.knowledge.connector;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WP6 Step2：WebDAV 连接器——mock 源站 PROPFIND 多状态 XML：目录递归/文件 etag/
 * 目录不入列/白名单后缀。guard 放行（源站即 127.0.0.1）。
 */
class WebDavConnectorTest {

    private HttpServer server;
    private String origin;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            boolean propfind = "PROPFIND".equalsIgnoreCase(exchange.getRequestMethod());
            String body;
            int status;
            if (propfind) {
                status = 207;   // Multi-Status
                body = propfindXml(path);
            } else {
                status = 200;
                body = "FILE-CONTENT-" + path;
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/xml; charset=utf-8");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        origin = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private String propfindXml(String path) {
        String self = path.endsWith("/") ? path : path + "/";
        return switch (path) {
            case "/", "" -> """
                    <?xml version="1.0"?>
                    <d:multistatus xmlns:d="DAV:">
                      <d:response><d:href>%s</d:href>
                        <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop>
                          <d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
                      <d:response><d:href>%sdocs/</d:href>
                        <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop>
                          <d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
                      <d:response><d:href>%sreadme.md</d:href>
                        <d:propstat><d:prop><d:getetag>"etag-readme"</d:getetag></d:prop>
                          <d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
                      <d:response><d:href>%slogo.png</d:href>
                        <d:propstat><d:prop><d:getetag>"etag-png"</d:getetag></d:prop>
                          <d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
                    </d:multistatus>""".formatted(self, self, self, self);
            default -> {
                String base = path.endsWith("/") ? path : path + "/";
                yield """
                        <?xml version="1.0"?>
                        <d:multistatus xmlns:d="DAV:">
                          <d:response><d:href>%s</d:href>
                            <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop>
                              <d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
                          <d:response><d:href>%sreport.pdf</d:href>
                            <d:propstat><d:prop><d:getetag>"etag-report"</d:getetag></d:prop>
                              <d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
                        </d:multistatus>""".formatted(base, base);
            }
        };
    }

    @Test
    void list_recursesDirectories_filesWithEtag() throws Exception {
        WebDavConnector connector = new WebDavConnector(
                Map.of("baseUrl", origin + "/", "username", "user", "password", "pass"),
                url -> true, new FetchLimiter(0, Long.MAX_VALUE));

        List<KnowledgeConnectorSpi.ExternalDoc> docs = connector.list();
        List<String> ids = docs.stream().map(KnowledgeConnectorSpi.ExternalDoc::externalId).toList();

        assertTrue(ids.contains(origin + "/readme.md"), "根目录文件应入列");
        assertTrue(ids.contains(origin + "/docs/report.pdf"), "子目录递归文件应入列");
        assertFalse(ids.stream().anyMatch(id -> id.endsWith("/docs")), "目录本身不得作为文档入列");
        assertFalse(ids.stream().anyMatch(id -> id.endsWith(".png")), "非白名单后缀不得入列");

        KnowledgeConnectorSpi.ExternalDoc readme = docs.stream()
                .filter(d -> d.externalId().endsWith("readme.md")).findFirst().orElseThrow();
        assertEquals("\"etag-readme\"", readme.etag(), "文件 etag 应取 getetag");
    }

    @Test
    void fetch_downloadsWithBasicAuth() throws Exception {
        WebDavConnector connector = new WebDavConnector(
                Map.of("baseUrl", origin + "/", "username", "user", "password", "pass"),
                url -> true, new FetchLimiter(0, Long.MAX_VALUE));
        byte[] body = connector.fetch(new KnowledgeConnectorSpi.ExternalDoc(
                origin + "/readme.md", "\"etag-readme\"", "readme.md"));
        assertEquals("FILE-CONTENT-/readme.md", new String(body, StandardCharsets.UTF_8));
    }
}
