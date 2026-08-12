// agent-platform/backend/src/test/java/com/superprogrammer/auth/security/JwtUtilTest.java
package com.superprogrammer.auth.security;

import io.jsonwebtoken.ExpiredJwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JwtUtilTest {

    private JwtUtil jwtUtil;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil();
        jwtUtil.setSecret("dGVzdFNlY3JldEtleUZvclRlc3RpbmdQdXJwb3Nlc09ubHlMb25nRW5vdWdo");
        jwtUtil.setAccessExpiration(60000L);
        jwtUtil.setRefreshExpiration(3600000L);
    }

    @Test
    void generateAccessToken_shouldContainUserIdAndUsername() {
        Long userId = 1L;
        String username = "admin";
        List<String> roles = Arrays.asList("admin");

        String token = jwtUtil.generateAccessToken(userId, username, roles);

        assertNotNull(token);
        assertFalse(token.isEmpty());
        assertEquals(userId, jwtUtil.getUserIdFromToken(token));
        assertEquals(username, jwtUtil.getUsernameFromToken(token));
        assertEquals(roles, jwtUtil.getRolesFromToken(token));
    }

    @Test
    void generateRefreshToken_shouldContainUserId() {
        Long userId = 1L;

        String token = jwtUtil.generateRefreshToken(userId);

        assertNotNull(token);
        assertEquals(userId, jwtUtil.getUserIdFromToken(token));
        assertEquals("refresh", jwtUtil.getTypeFromToken(token));
    }

    @Test
    void isTokenValid_shouldReturnTrueForValidToken() {
        String token = jwtUtil.generateAccessToken(1L, "admin", Arrays.asList("admin"));

        assertTrue(jwtUtil.isTokenValid(token));
    }

    @Test
    void isTokenExpired_shouldReturnFalseForFreshToken() {
        String token = jwtUtil.generateAccessToken(1L, "admin", Arrays.asList("admin"));

        assertFalse(jwtUtil.isTokenExpired(token));
    }

    @Test
    void isTokenExpired_shouldReturnTrueForExpiredToken() {
        jwtUtil.setAccessExpiration(1L);
        String token = jwtUtil.generateAccessToken(1L, "admin", Arrays.asList("admin"));

        // 等待token过期
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        assertTrue(jwtUtil.isTokenExpired(token));
    }

    @Test
    void getRemainingTtl_shouldReturnPositiveForValidToken() {
        String token = jwtUtil.generateAccessToken(1L, "admin", Arrays.asList("admin"));

        long ttl = jwtUtil.getRemainingTtl(token);

        assertTrue(ttl > 0);
    }

    @Test
    void getTokenId_shouldReturnUniqueId() {
        String token1 = jwtUtil.generateAccessToken(1L, "admin", Arrays.asList("admin"));
        String token2 = jwtUtil.generateAccessToken(1L, "admin", Arrays.asList("admin"));

        String jti1 = jwtUtil.getTokenId(token1);
        String jti2 = jwtUtil.getTokenId(token2);

        assertNotNull(jti1);
        assertNotNull(jti2);
        assertNotEquals(jti1, jti2);
    }

    @Test
    void isTokenValid_shouldReturnFalseForMalformedToken() {
        assertFalse(jwtUtil.isTokenValid("this.is.not-a-valid-token"));
    }

    // 安全体系 S2 · A8（SEC-FR-008）：sid claim 签发/读取回环；旧签名（无 sid）→ null
    @Test
    void sidClaim_roundTrip_oldSignatureHasNullSid() {
        String withSid = jwtUtil.generateAccessToken(1L, "admin", Arrays.asList("admin"), 60000L, "sid-1");
        assertEquals("sid-1", jwtUtil.getSidFromToken(withSid));

        String refreshWithSid = jwtUtil.generateRefreshToken(1L, "sid-1");
        assertEquals("sid-1", jwtUtil.getSidFromToken(refreshWithSid));

        String legacy = jwtUtil.generateAccessToken(1L, "admin", Arrays.asList("admin"));
        assertNull(jwtUtil.getSidFromToken(legacy));

        String legacyRefresh = jwtUtil.generateRefreshToken(1L);
        assertNull(jwtUtil.getSidFromToken(legacyRefresh));
    }
}
