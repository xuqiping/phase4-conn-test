package com.superprogrammer.common.health;

import com.superprogrammer.runtime.config.RuntimeGatewayProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 运维系统 OPS-FR-09：sidecar 探活健康检查单测。
 * 用 JDK 内置 HttpServer 起真实 HTTP（正/反向/超时三态），比 mock HttpClient 更贴近线上。
 */
class SidecarHealthIndicatorTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private SidecarHealthIndicator startServerAndIndicator(int status, String body, long delayMillis) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", exchange -> {
            try {
                if (delayMillis > 0) {
                    Thread.sleep(delayMillis);
                }
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.start();

        RuntimeGatewayProperties properties = new RuntimeGatewayProperties();
        properties.setSidecarBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        return new SidecarHealthIndicator(properties);
    }

    // ---- 正向：200 + {"status":"UP"} → UP，带 baseUrl/httpStatus 明细 ----

    @Test
    void upWhenSidecarRespondsUp() throws IOException {
        SidecarHealthIndicator indicator = startServerAndIndicator(200, "{\"status\": \"UP\", \"service\": \"runtime-sidecar\"}", 0);

        Health health = indicator.health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals(200, health.getDetails().get("httpStatus"));
        assertTrue(((String) health.getDetails().get("baseUrl")).startsWith("http://127.0.0.1:"));
    }

    // ---- 反向①：HTTP 500 → DOWN 带 body 摘要 ----

    @Test
    void downWhenSidecarReturns500() throws IOException {
        SidecarHealthIndicator indicator = startServerAndIndicator(500, "boom", 0);

        Health health = indicator.health();

        assertEquals(Status.DOWN, health.getStatus());
        assertEquals(500, health.getDetails().get("httpStatus"));
        assertEquals("boom", health.getDetails().get("body"));
    }

    // ---- 反向②：200 但 body 非 UP → DOWN（进程活着但自报不健康）----

    @Test
    void downWhenBodyNotUp() throws IOException {
        SidecarHealthIndicator indicator = startServerAndIndicator(200, "{\"status\": \"DOWN\"}", 0);

        Health health = indicator.health();

        assertEquals(Status.DOWN, health.getStatus());
    }

    // ---- 反向③：连接被拒（sidecar 没起）→ DOWN 带 error，不抛异常 ----

    @Test
    void downWhenConnectionRefused() {
        RuntimeGatewayProperties properties = new RuntimeGatewayProperties();
        properties.setSidecarBaseUrl("http://127.0.0.1:1"); // 保留端口必拒连
        SidecarHealthIndicator indicator = new SidecarHealthIndicator(properties);

        Health health = indicator.health();

        assertEquals(Status.DOWN, health.getStatus());
        assertTrue(((String) health.getDetails().get("error")).contains("ConnectException"));
    }

    // ---- 反向④：响应超 2s 超时 → DOWN（HttpTimeoutException）----

    @Test
    void downWhenResponseTimesOut() throws IOException {
        SidecarHealthIndicator indicator = startServerAndIndicator(200, "{\"status\": \"UP\"}", 3000);

        long start = System.nanoTime();
        Health health = indicator.health();
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertEquals(Status.DOWN, health.getStatus());
        assertTrue(((String) health.getDetails().get("error")).contains("Timeout"));
        assertTrue(elapsedMillis < 2900, "须在 2s 超时线内返回，实际 " + elapsedMillis + "ms");
    }
}
