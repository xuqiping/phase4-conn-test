package com.superprogrammer.knowledge.config;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OpenSearchHealthIndicatorTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void reportsDisabledWithoutOpeningConnection() {
        OpenSearchProperties properties = properties(false, "http://127.0.0.1:1");

        Health health = new OpenSearchHealthIndicator(properties).health();

        assertEquals(Status.UNKNOWN, health.getStatus());
        assertEquals("disabled", health.getDetails().get("state"));
    }

    @Test
    void reportsUpForReachableCluster() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = "{\"cluster_name\":\"test\",\"version\":{\"number\":\"2.13.0\"}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        OpenSearchProperties properties = properties(true,
                "http://127.0.0.1:" + server.getAddress().getPort());

        Health health = new OpenSearchHealthIndicator(properties).health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals("up", health.getDetails().get("state"));
    }

    @Test
    void reportsDownWithoutLeakingCredentials() {
        OpenSearchProperties properties = properties(true, "http://127.0.0.1:1");
        properties.setUsername("secret-user");
        properties.setPassword("secret-password");

        Health health = new OpenSearchHealthIndicator(properties).health();

        assertEquals(Status.DOWN, health.getStatus());
        String details = health.getDetails().toString();
        org.junit.jupiter.api.Assertions.assertFalse(details.contains("secret-user"));
        org.junit.jupiter.api.Assertions.assertFalse(details.contains("secret-password"));
    }

    private static OpenSearchProperties properties(boolean enabled, String url) {
        OpenSearchProperties properties = new OpenSearchProperties();
        properties.setEnabled(enabled);
        properties.setUrl(url);
        properties.setConnectTimeout(Duration.ofMillis(200));
        properties.setRequestTimeout(Duration.ofMillis(500));
        return properties;
    }
}
