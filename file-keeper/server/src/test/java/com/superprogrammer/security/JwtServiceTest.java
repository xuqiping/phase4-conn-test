package com.superprogrammer.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@ActiveProfiles("test")
class JwtServiceTest {

    @Autowired
    private JwtService jwtService;

    @Test
    void createsAndParsesAccessToken() {
        String token = jwtService.createAccessToken(42L, "super_admin", "active");

        AuthPrincipal principal = jwtService.parseAccessToken(token);

        assertEquals(42L, principal.userId());
        assertEquals("super_admin", principal.role());
        assertEquals("active", principal.status());
    }

    @Test
    void rejectsMalformedToken() {
        assertThrows(RuntimeException.class, () -> jwtService.parseAccessToken("not-a-jwt"));
    }

    @Test
    void rejectsTokenSignedWithDifferentSecret() {
        SecretKey differentKey = Keys.hmacShaKeyFor(
                "different-file-keeper-jwt-secret-at-least-32-bytes".getBytes(StandardCharsets.UTF_8)
        );
        Instant now = Instant.now();
        String token = Jwts.builder()
                .subject("42")
                .claim("role", "super_admin")
                .claim("status", "active")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(15, ChronoUnit.MINUTES)))
                .signWith(differentKey)
                .compact();

        assertThrows(RuntimeException.class, () -> jwtService.parseAccessToken(token));
    }
}
