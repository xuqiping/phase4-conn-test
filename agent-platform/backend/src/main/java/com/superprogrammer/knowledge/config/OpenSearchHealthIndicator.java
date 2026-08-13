package com.superprogrammer.knowledge.config;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

@Component("openSearchHealthIndicator")
public class OpenSearchHealthIndicator implements HealthIndicator {

    private final OpenSearchProperties properties;

    public OpenSearchHealthIndicator(OpenSearchProperties properties) {
        this.properties = properties;
    }

    @Override
    public Health health() {
        if (!properties.isEnabled()) {
            return Health.unknown().withDetail("state", "disabled").build();
        }
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(properties.getConnectTimeout())
                    .build();
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(properties.getUrl()))
                    .timeout(properties.getRequestTimeout())
                    .GET();
            if (hasText(properties.getUsername())) {
                String raw = properties.getUsername() + ":" + nullToEmpty(properties.getPassword());
                request.header("Authorization", "Basic " + Base64.getEncoder()
                        .encodeToString(raw.getBytes(StandardCharsets.UTF_8)));
            }
            HttpResponse<Void> response = client.send(request.build(), HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return Health.up().withDetail("state", "up").withDetail("endpoint", safeEndpoint()).build();
            }
            return Health.down().withDetail("state", "down")
                    .withDetail("endpoint", safeEndpoint()).withDetail("httpStatus", response.statusCode()).build();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return down("interrupted");
        } catch (Exception error) {
            return down(error.getClass().getSimpleName());
        }
    }

    private Health down(String errorType) {
        return Health.down().withDetail("state", "down")
                .withDetail("endpoint", safeEndpoint()).withDetail("errorType", errorType).build();
    }

    private String safeEndpoint() {
        URI uri = URI.create(properties.getUrl());
        return uri.getScheme() + "://" + uri.getHost() + (uri.getPort() < 0 ? "" : ":" + uri.getPort());
    }

    private static boolean hasText(String value) { return value != null && !value.isBlank(); }
    private static String nullToEmpty(String value) { return value == null ? "" : value; }
}
