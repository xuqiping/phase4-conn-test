package com.superprogrammer.admin;

import com.superprogrammer.support.TestStoreConfig;
import org.junit.jupiter.api.BeforeEach;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestStoreConfig.class)
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:admin_entitlement;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AdminEntitlementTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void cleanUp() {
        jdbcTemplate.update("delete from admin_audit_logs");
        jdbcTemplate.update("delete from user_module_entitlements");
        jdbcTemplate.update("delete from users");
    }

    @Test
    void adminCanGrantUpdateAndRevokeModuleEntitlement() throws Exception {
        Long adminId = insertUser("admin@example.com", "super_admin", "active");
        Long userId = insertUser("target@example.com", "user", "active");
        String accessToken = adminAccessToken();

        // Grant files module
        MvcResult grantResult = mockMvc.perform(post("/api/admin/users/" + userId + "/entitlements")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType("application/json")
                        .content("{\"moduleCode\":\"files\",\"expiresAt\":\"2099-12-31T23:59:59+08:00\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.moduleCode").value("files"))
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andReturn();

        String body = grantResult.getResponse().getContentAsString();
        Long entitlementId = Long.valueOf(body.replaceAll(".*\"id\":(\\d+).*", "$1"));

        // Verify DB record
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from user_module_entitlements where user_id = ? and module_code = 'files' and enabled = true and deleted = 0",
                Integer.class, userId);
        assertEquals(1, count);

        // Verify audit log
        Integer auditCount = jdbcTemplate.queryForObject(
                "select count(*) from admin_audit_logs where admin_user_id = ? and action = 'entitlement.grant' and target_type = 'entitlement'",
                Integer.class, adminId);
        assertEquals(1, auditCount);

        // List entitlements
        mockMvc.perform(get("/api/admin/users/" + userId + "/entitlements")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].moduleCode").value("files"));

        // Update: disable
        mockMvc.perform(put("/api/admin/users/" + userId + "/entitlements/" + entitlementId)
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType("application/json")
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(false));

        // Revoke
        mockMvc.perform(delete("/api/admin/users/" + userId + "/entitlements/" + entitlementId)
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk());

        // Verify soft-deleted
        Integer activeCount = jdbcTemplate.queryForObject(
                "select count(*) from user_module_entitlements where id = ? and deleted = 0",
                Integer.class, entitlementId);
        assertEquals(0, activeCount);
    }

    @Test
    void rejectDuplicateModuleGrant() throws Exception {
        insertUser("admin@example.com", "super_admin", "active");
        Long userId = insertUser("dup@example.com", "user", "active");
        String accessToken = adminAccessToken();

        // First grant
        mockMvc.perform(post("/api/admin/users/" + userId + "/entitlements")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType("application/json")
                        .content("{\"moduleCode\":\"files\"}"))
                .andExpect(status().isOk());

        // Duplicate grant
        mockMvc.perform(post("/api/admin/users/" + userId + "/entitlements")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType("application/json")
                        .content("{\"moduleCode\":\"files\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409));
    }

    @Test
    void rejectInvalidModuleCode() throws Exception {
        insertUser("admin@example.com", "super_admin", "active");
        Long userId = insertUser("invalid@example.com", "user", "active");
        String accessToken = adminAccessToken();

        mockMvc.perform(post("/api/admin/users/" + userId + "/entitlements")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType("application/json")
                        .content("{\"moduleCode\":\"invalid_module\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    private String adminAccessToken() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/admin/auth/login")
                        .contentType("application/json")
                        .content("{\"identifier\":\"admin@example.com\",\"password\":\"Password123!\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return result.getResponse().getContentAsString().replaceAll(".*\\\"accessToken\\\":\\\"([^\\\"]+)\\\".*", "$1");
    }

    private Long insertUser(String email, String role, String status) {
        jdbcTemplate.update(
                "insert into users (email, password_hash, role, status, email_verified, phone_verified, device_limit, offline_cache_minutes, created_by, created_at, updated_by, updated_at, deleted) " +
                        "values (?, ?, ?, ?, true, false, 1, 0, 0, CURRENT_TIMESTAMP, 0, CURRENT_TIMESTAMP, 0)",
                email, passwordEncoder.encode("Password123!"), role, status
        );
        return jdbcTemplate.queryForObject("select id from users where email = ? order by id desc limit 1", Long.class, email);
    }
}
