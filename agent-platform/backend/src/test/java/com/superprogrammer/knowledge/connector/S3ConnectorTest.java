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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * WP6 Step2：S3 连接器——mock S3 兼容源站（SDK 只发不验响应签名，XML 应答即可）：
 * prefix 过滤/etag 透传/目录占位与非白名单跳过/下载字节。
 */
class S3ConnectorTest {

    private HttpServer server;
    private S3Connector connector;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            byte[] body;
            if ("GET".equals(exchange.getRequestMethod()) && !path.contains("?")
                    && !path.endsWith(".md") && !path.endsWith(".pdf")) {
                // ListObjectsV2（path-style：/{bucket}?list-type=2...）
                body = listingXml().getBytes(StandardCharsets.UTF_8);
            } else {
                body = ("S3-OBJECT-" + path).getBytes(StandardCharsets.UTF_8);
            }
            exchange.getResponseHeaders().add("Content-Type", "application/xml");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        connector = new S3Connector(Map.of(
                "endpoint", "http://127.0.0.1:" + server.getAddress().getPort(),
                "bucket", "docs",
                "prefix", "wiki/",
                "accessKey", "ak",
                "secretKey", "sk"), new FetchLimiter(0, Long.MAX_VALUE));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        connector.close();
    }

    private String listingXml() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                  <Name>docs</Name><Prefix>wiki/</Prefix><KeyCount>4</KeyCount><MaxKeys>200</MaxKeys>
                  <IsTruncated>false</IsTruncated>
                  <Contents><Key>wiki/dir/</Key><ETag>"d"</ETag></Contents>
                  <Contents><Key>wiki/a.md</Key><ETag>"etag-a"</ETag></Contents>
                  <Contents><Key>wiki/b.pdf</Key><ETag>"etag-b"</ETag></Contents>
                  <Contents><Key>wiki/c.png</Key><ETag>"etag-c"</ETag></Contents>
                </ListBucketResult>""";
    }

    @Test
    void list_prefixFilteredEtagPassthrough() {
        List<KnowledgeConnectorSpi.ExternalDoc> docs = connector.list();
        List<String> ids = docs.stream().map(KnowledgeConnectorSpi.ExternalDoc::externalId).toList();
        assertEquals(2, docs.size());
        assertEquals("\"etag-a\"", docs.get(0).etag());
        assertEquals("\"etag-b\"", docs.get(1).etag());
        assertFalse(ids.contains("wiki/dir/"), "目录占位 key 不入列");
        assertFalse(ids.contains("wiki/c.png"), "非白名单对象不入列");
    }

    @Test
    void fetch_returnsObjectBytes() throws Exception {
        byte[] body = connector.fetch(new KnowledgeConnectorSpi.ExternalDoc("wiki/a.md", "\"etag-a\"", "a.md"));
        assertEquals("S3-OBJECT-/docs/wiki/a.md", new String(body, StandardCharsets.UTF_8));
    }
}
