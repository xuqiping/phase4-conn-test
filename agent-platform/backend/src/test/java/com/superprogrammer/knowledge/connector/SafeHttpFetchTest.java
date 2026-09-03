package com.superprogrammer.knowledge.connector;

import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.security.SsrfGuard;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WP6 Step2：SSRF 防线——真 SsrfGuard 渗透字面量直拒 + 重定向逐跳复校
 * （手动跟随核心价值：自动跟随会带着校验过的首跳 302 直进内网）。
 */
class SafeHttpFetchTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void ssrf_literals_rejectedBeforeAnyRequest() {
        // 渗透用例（坑点表）：回环/云元数据/全零/私网——guard 咽喉点直接拒绝，零出站
        String[] attacks = {
                "http://localhost:8080/admin",
                "http://169.254.169.254/latest/meta-data/",
                "http://0.0.0.0/x",
                "http://10.0.0.5/internal",
                "file:///etc/passwd"          // 非常规协议
        };
        for (String url : attacks) {
            // SsrfGuard.validate 返回 void、违规抛异常——包成谓词：抛=拒
            java.util.function.Predicate<String> guard = u -> {
                SsrfGuard.validate(u);
                return true;
            };
            BusinessException e = assertThrows(BusinessException.class, () ->
                    SafeHttpFetch.get(url, guard, new FetchLimiter(0, Long.MAX_VALUE),
                            Map.of(), Duration.ofSeconds(2)),
                    "应被 SSRF 防护拒绝: " + url);
            assertTrue(e.getMessage() != null);
        }
    }

    @Test
    void redirect_toInternalAddress_blockedPerHop() throws Exception {
        // mock 源站绑 127.0.0.1（真 SsrfGuard 会拒首跳，故用「只拒 169.254」的窄 guard 模拟内网重定向场景）
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = "".getBytes();
            exchange.getResponseHeaders().add("Location", "http://169.254.169.254/latest/meta-data/");
            exchange.sendResponseHeaders(302, body.length == 0 ? -1 : body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                if (body.length > 0) out.write(body);
            }
        });
        server.start();
        String seed = "http://127.0.0.1:" + server.getAddress().getPort() + "/page";

        BusinessException e = assertThrows(BusinessException.class, () ->
                SafeHttpFetch.get(seed, url -> !url.contains("169.254"),
                        new FetchLimiter(0, Long.MAX_VALUE), Map.of(), Duration.ofSeconds(2)));
        assertTrue(e.getMessage().contains("SSRF"), "第二跳必须被逐跳校验拦下: " + e.getMessage());
    }
}
