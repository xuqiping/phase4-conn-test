package com.superprogrammer.admin;

import com.superprogrammer.support.TestStoreConfig;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 4 运行验证：超管 access token 1 分钟过期 + refresh token 可刷新。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestStoreConfig.class)
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:admin_token_refresh;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AdminTokenRefreshVerificationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Value("${file-keeper.auth.jwt.secret}")
    private String jwtSecret;

    private static final Pattern ACCESS_TOKEN_PATTERN = Pattern.compile(".*\"accessToken\":\"([^\"]+)\".*");
    private static final Pattern REFRESH_TOKEN_PATTERN = Pattern.compile(".*\"refreshToken\":\"([^\"]+)\".*");

    @Test
    void superAdminAccessTokenExpiresInOneMinuteAndRefreshSucceeds() throws Exception {
        insertUser("adm@example.com", "super_admin", "active");

        // 1. 登录超管账号（账号 adm，密码 adm123）
        MvcResult loginResult = mockMvc.perform(post("/api/admin/auth/login")
                        .contentType("application/json")
                        .content("{\"identifier\":\"adm@example.com\",\"password\":\"adm123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.expiresInSeconds").value(60))
                .andReturn();

        String loginBody = loginResult.getResponse().getContentAsString();
        String accessToken = extractToken(loginBody, ACCESS_TOKEN_PATTERN);
        String refreshToken = extractToken(loginBody, REFRESH_TOKEN_PATTERN);
        assertNotNull(accessToken);
        assertNotNull(refreshToken);

        // 2. 验证 access token 实际过期时间为 1 分钟（60 秒）
        Claims claims = parseAccessToken(accessToken);
        long expirationSeconds = (claims.getExpiration().getTime() - claims.getIssuedAt().getTime()) / 1000;
        assertEquals(60, expirationSeconds, "超管 access token 应 60 秒过期");

        // 3. 使用 refresh token 刷新（模拟 1 分钟后 token 过期前的自动刷新）
        MvcResult refreshResult = mockMvc.perform(post("/api/admin/auth/refresh")
                        .contentType("application/json")
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isString())
                .andExpect(jsonPath("$.data.expiresInSeconds").value(60))
                .andReturn();

        String newAccessToken = extractToken(refreshResult.getResponse().getContentAsString(), ACCESS_TOKEN_PATTERN);
        assertNotNull(newAccessToken);
        assertNotEquals(accessToken, newAccessToken, "刷新后应返回新的 access token");

        // 4. 用新的 access token 访问需要认证的 admin 接口，验证不需要重新登录
        mockMvc.perform(get("/api/admin/users")
                        .header("Authorization", "Bearer " + newAccessToken))
                .andExpect(status().isOk());
    }

    @Test
    void superAdminTokenRefreshAfterOneMinuteStillWorks() throws Exception {
        insertUser("adm2@example.com", "super_admin", "active");

        MvcResult loginResult = mockMvc.perform(post("/api/admin/auth/login")
                        .contentType("application/json")
                        .content("{\"identifier\":\"adm2@example.com\",\"password\":\"adm123\"}"))
                .andExpect(status().isOk())
                .andReturn();

        String loginBody = loginResult.getResponse().getContentAsString();
        String refreshToken = extractToken(loginBody, REFRESH_TOKEN_PATTERN);

        // 等待 61 秒，让 access token 真正过期，再用 refresh token 刷新
        Thread.sleep(61_000);

        MvcResult refreshResult = mockMvc.perform(post("/api/admin/auth/refresh")
                        .contentType("application/json")
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isString())
                .andExpect(jsonPath("$.data.expiresInSeconds").value(60))
                .andReturn();

        String newAccessToken = extractToken(refreshResult.getResponse().getContentAsString(), ACCESS_TOKEN_PATTERN);

        mockMvc.perform(get("/api/admin/users")
                        .header("Authorization", "Bearer " + newAccessToken))
                .andExpect(status().isOk());
    }

    private void insertUser(String email, String role, String status) {
        jdbcTemplate.update(
                "insert into users (email, password_hash, role, status, email_verified, phone_verified, device_limit, offline_cache_minutes, created_by, created_at, updated_by, updated_at, deleted) " +
                        "values (?, ?, ?, ?, true, false, 1, 0, 0, CURRENT_TIMESTAMP, 0, CURRENT_TIMESTAMP, 0)",
                email,
                passwordEncoder.encode("adm123"),
                role,
                status
        );
    }

    private String extractToken(String body, Pattern pattern) {
        Matcher matcher = pattern.matcher(body);
        return matcher.find() ? matcher.group(1) : null;
    }

    private Claims parseAccessToken(String token) {
        SecretKey key = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
