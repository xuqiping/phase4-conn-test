package com.superprogrammer.security;

import com.superprogrammer.config.AuthProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

@Service
@RequiredArgsConstructor
public class JwtService {

    private final AuthProperties authProperties;

    public String createAccessToken(Long userId, String role, String status) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(authProperties.getJwt().getAccessTokenMinutes(), ChronoUnit.MINUTES);
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("role", role)
                .claim("status", status)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(signingKey())
                .compact();
    }

    public AuthPrincipal parseAccessToken(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(signingKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return new AuthPrincipal(
                Long.valueOf(claims.getSubject()),
                claims.get("role", String.class),
                claims.get("status", String.class)
        );
    }

    private SecretKey signingKey() {
        byte[] secret = authProperties.getJwt().getSecret().getBytes(StandardCharsets.UTF_8);
        return Keys.hmacShaKeyFor(secret);
    }
}
