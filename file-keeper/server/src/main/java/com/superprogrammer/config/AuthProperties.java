package com.superprogrammer.config;

import com.superprogrammer.authorization.service.SignedEntitlementSigner;
import jakarta.annotation.PostConstruct;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.AccessLevel;
import lombok.Data;
import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;

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
    private Entitlement entitlement = new Entitlement();

    @Getter(AccessLevel.NONE)
    private transient PrivateKey entitlementPrivateKey;

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

    @PostConstruct
    void validateEntitlementPrivateKey() {
        String pem = entitlement == null ? null : entitlement.getPrivateKeyPem();
        if (pem == null || pem.isBlank()) {
            throw new IllegalStateException("Entitlement signing private key must not be blank (FILE_KEEPER_AUTH_PRIVATE_KEY)");
        }
        entitlementPrivateKey = SignedEntitlementSigner.decodePrivateKeyPem(pem);
        if (entitlementPrivateKey == null) {
            throw new IllegalStateException("Entitlement signing private key PEM is invalid");
        }
    }

    public PrivateKey getEntitlementPrivateKey() {
        if (entitlementPrivateKey == null) {
            entitlementPrivateKey = SignedEntitlementSigner.decodePrivateKeyPem(entitlement.getPrivateKeyPem());
        }
        return entitlementPrivateKey;
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

    @Data
    public static class Entitlement {
        /**
         * Ed25519 私钥（PKCS#8 PEM），用于签发授权凭据。
         * 生产环境必须配置，缺失则服务拒绝启动。
         */
        private String privateKeyPem;
    }
}
