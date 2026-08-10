package com.superprogrammer.common.health;

import com.superprogrammer.runtime.config.RuntimeGatewayProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;
import java.time.Duration;

/**
 * 运维系统 OPS-FR-09：sidecar 探活聚合进 /actuator/health。
 * HTTP GET {sidecarBaseUrl}/health，2s 超时；非 200 / 超时 / 拒连一律 DOWN（带原因明细）。
 * 仅在 runtime.gateway.mode=sidecar 时注册（嵌入式模式无 sidecar 可探）。
 */
@Component("sidecarHealthIndicator")
@ConditionalOnProperty(prefix = "runtime.gateway", name = "mode", havingValue = "sidecar", matchIfMissing = true)
public class SidecarHealthIndicator implements HealthIndicator {

    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    private final RuntimeGatewayProperties properties;
    private final HttpClient httpClient;

    /** 多构造器（测试可注入 mock HttpClient）须显式 @Autowired，否则 Spring 回退找无参构造启动即炸。 */
    @Autowired
    public SidecarHealthIndicator(RuntimeGatewayProperties properties) {
        this(properties, HttpClient.newBuilder().connectTimeout(TIMEOUT).build());
    }

    /** 测试可注入 mock HttpClient。 */
    SidecarHealthIndicator(RuntimeGatewayProperties properties, HttpClient httpClient) {
        this.properties = properties;
        this.httpClient = httpClient;
    }

    @Override
    public Health health() {
        String baseUrl = properties.getSidecarBaseUrl();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/health"))
                    .timeout(TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            boolean up = response.statusCode() == 200 && response.body() != null
                    && response.body().contains("\"UP\"");
            Health.Builder builder = (up ? Health.up() : Health.down())
                    .withDetail("baseUrl", baseUrl)
                    .withDetail("httpStatus", response.statusCode());
            if (!up) {
                builder.withDetail("body", abbreviate(response.body()));
            }
            return builder.build();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Health.down().withDetail("baseUrl", baseUrl).withDetail("error", "interrupted").build();
        } catch (Exception e) {
            // 超时(HttpTimeoutException)/拒连(ConnectException)等统一 DOWN，不向上抛炸整个 health 端点
            return Health.down().withDetail("baseUrl", baseUrl)
                    .withDetail("error", e.getClass().getSimpleName() + ": " + e.getMessage())
                    .build();
        }
    }

    private static String abbreviate(String body) {
        if (body == null) {
            return null;
        }
        return body.length() <= 200 ? body : body.substring(0, 200) + "...";
    }
}
