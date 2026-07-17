// agent-platform/backend/src/main/java/com/superprogrammer/auth/security/JwtUtil.java
package com.superprogrammer.auth.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Component
@Getter
@Setter
@ConfigurationProperties(prefix = "jwt")
public class JwtUtil {

    private String secret;
    private Long accessExpiration;
    private Long refreshExpiration;

    /**
     * 已泄露进 git 历史的弱默认密钥（base64 原文 + 解码明文）。
     * <p>安全审计 #2：命中即拒绝启动——旧默认值随代码提交进仓库，任何能看到代码的人都拿到了它，
     * 可伪造任意身份令牌。必须轮换后经环境变量 JWT_SECRET 注入新密钥。
     */
    private static final Set<String> KNOWN_WEAK_SECRETS = Set.of(
            "bXlTdXBlclNlY3JldEtleUZvckFnZW50UGxhdGZvcm1Qcm9qZWN0MjAyNg==",
            "mySuperSecretKeyForAgentPlatformProject2026"
    );

    /**
     * 启动校验：密钥缺失或命中已知弱默认值 → 抛异常拒绝启动（fail-fast > 静默用弱密钥运行）。
     */
    @PostConstruct
    void validateSecret() {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "JWT_SECRET 未配置：请通过环境变量 JWT_SECRET 注入（生成: openssl rand -base64 64 | tr -d '\\n'）。禁止使用空密钥启动。");
        }
        if (KNOWN_WEAK_SECRETS.contains(secret.trim())) {
            throw new IllegalStateException(
                    "JWT_SECRET 命中已泄露的弱默认值（见安全审计 #2）。请轮换并经环境变量注入新密钥后重启。");
        }
    }

    private SecretKey getSigningKey() {
        byte[] keyBytes = Base64.getDecoder().decode(secret);
        return Keys.hmacShaKeyFor(keyBytes);
    }

    public String generateAccessToken(Long userId, String username, List<String> roles) {
        return generateAccessToken(userId, username, roles, accessExpiration);
    }

    public String generateAccessToken(Long userId, String username, List<String> roles, Long expirationMs) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + expirationMs);

        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("username", username)
                .claim("roles", roles)
                .claim("type", "access")
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiration(expiryDate)
                .signWith(getSigningKey())
                .compact();
    }

    public String generateRefreshToken(Long userId) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + refreshExpiration);

        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("type", "refresh")
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiration(expiryDate)
                .signWith(getSigningKey())
                .compact();
    }

    public Long getUserIdFromToken(String token) {
        Claims claims = parseToken(token);
        return Long.parseLong(claims.getSubject());
    }

    public String getUsernameFromToken(String token) {
        Claims claims = parseToken(token);
        return claims.get("username", String.class);
    }

    @SuppressWarnings("unchecked")
    public List<String> getRolesFromToken(String token) {
        Claims claims = parseToken(token);
        return claims.get("roles", List.class);
    }

    public String getTypeFromToken(String token) {
        Claims claims = parseToken(token);
        return claims.get("type", String.class);
    }

    public String getTokenId(String token) {
        Claims claims = parseToken(token);
        return claims.getId();
    }

    public boolean isTokenValid(String token) {
        try {
            parseToken(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    public boolean isTokenExpired(String token) {
        try {
            Claims claims = parseToken(token);
            return claims.getExpiration().before(new Date());
        } catch (ExpiredJwtException e) {
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    public long getRemainingTtl(String token) {
        Claims claims = parseToken(token);
        Date expiration = claims.getExpiration();
        long remaining = expiration.getTime() - System.currentTimeMillis();
        return Math.max(remaining, 0);
    }

    private Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
