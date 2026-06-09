package com.superprogrammer.admin;

import com.superprogrammer.support.TestStoreConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestStoreConfig.class)
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:admin_user_disable_enable;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AdminUserDisableEnableTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void disablingUserRevokesRefreshTokensAndEnableAllowsLoginAgain() throws Exception {
        Long adminId = insertUser("admin@example.com", "super_admin", "active");
        Long userId = insertUser("active-user@example.com", "user", "active");
        String adminAccessToken = adminAccessToken();
        String userRefreshToken = loginAndReadRefreshToken("active-user@example.com");

        // Disable the user
        mockMvc.perform(post("/api/admin/users/" + userId + "/disable")
                        .header("Authorization", "Bearer " + adminAccessToken)
                        .contentType("application/json")
                        .content("{\"note\":\"授权违规\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("disabled"));

        // Old refresh token should be revoked
        mockMvc.perform(post("/api/client/auth/refresh")
                        .contentType("application/json")
                        .content("{\"refreshToken\":\"" + userRefreshToken + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        // Disabled user cannot login
        mockMvc.perform(post("/api/client/auth/login")
                        .contentType("application/json")
                        .content("{\"identifier\":\"active-user@example.com\",\"password\":\"Password123!\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));

        // Enable the user
        mockMvc.perform(post("/api/admin/users/" + userId + "/enable")
                        .header("Authorization", "Bearer " + adminAccessToken)
                        .contentType("application/json")
                        .content("{\"note\":\"恢复使用\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("active"));

        // Enabled user can login again
        mockMvc.perform(post("/api/client/auth/login")
                        .contentType("application/json")
                        .content("{\"identifier\":\"active-user@example.com\",\"password\":\"Password123!\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user.status").value("active"));

        // Verify audit logs
        Integer disableAuditCount = jdbcTemplate.queryForObject(
                "select count(*) from admin_audit_logs where admin_user_id = ? and action = 'user.disable' and target_id = ?",
                Integer.class,
                adminId,
                String.valueOf(userId)
        );
        Integer enableAuditCount = jdbcTemplate.queryForObject(
                "select count(*) from admin_audit_logs where admin_user_id = ? and action = 'user.enable' and target_id = ?",
                Integer.class,
                adminId,
                String.valueOf(userId)
        );
        assertEquals(1, disableAuditCount);
        assertEquals(1, enableAuditCount);
    }

    private String adminAccessToken() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/admin/auth/login")
                        .contentType("application/json")
                        .content("{\"identifier\":\"admin@example.com\",\"password\":\"Password123!\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return result.getResponse().getContentAsString().replaceAll(".*\\\"accessToken\\\":\\\"([^\\\"]+)\\\".*", "$1");
    }

    private String loginAndReadRefreshToken(String identifier) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/client/auth/login")
                        .contentType("application/json")
                        .content("{\"identifier\":\"" + identifier + "\",\"password\":\"Password123!\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return result.getResponse().getContentAsString().replaceAll(".*\\\"refreshToken\\\":\\\"([^\\\"]+)\\\".*", "$1");
    }

    private Long insertUser(String email, String role, String status) {
        jdbcTemplate.update(
                "insert into users (email, password_hash, role, status, email_verified, phone_verified, device_limit, offline_cache_minutes, created_by, created_at, updated_by, updated_at, deleted) " +
                        "values (?, ?, ?, ?, true, false, 1, 0, 0, CURRENT_TIMESTAMP, 0, CURRENT_TIMESTAMP, 0)",
                email,
                passwordEncoder.encode("Password123!"),
                role,
                status
        );
        return jdbcTemplate.queryForObject("select id from users where email = ? order by id desc limit 1", Long.class, email);
    }
}
