package com.superprogrammer.config;

import jakarta.annotation.PostConstruct;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.nio.charset.StandardCharsets;

@Data
@Component
@Validated
@ConfigurationProperties(prefix = "file-keeper.auth")
public class AuthProperties {

    private static final String DEFAULT_JWT_SECRET = "change-this-file-keeper-jwt-secret-at-least-32-bytes";

    @Valid
    private Jwt jwt = new Jwt();
    private RefreshToken refreshToken = new RefreshToken();
    private Verification verification = new Verification();

    @PostConstruct
    void validateJwtSecret() {
        String secret = jwt == null ? null : jwt.getSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("JWT secret must not be blank");
        }
        if (DEFAULT_JWT_SECRET.equals(secret)) {
            throw new IllegalStateException("JWT secret must not use the default development value");
        }
        if (secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("JWT secret must be at least 32 UTF-8 bytes");
        }
    }

    @Data
    public static class Jwt {
        @NotBlank(message = "JWT secret must not be blank")
        private String secret;
        private long accessTokenMinutes = 15;
        private long clientAccessTokenHours = 24;
    }

    @Data
    public static class RefreshToken {
        private long days = 7;
    }

    @Data
    public static class Verification {
        private long codeMinutes = 10;
        private long verifiedMinutes = 30;
        private String devFixedCode;
    }
}
