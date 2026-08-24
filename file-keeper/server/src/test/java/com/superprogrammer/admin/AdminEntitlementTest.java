package com.superprogrammer.admin;

import com.superprogrammer.admin.controller.AdminAnonymousDeviceController;
import com.superprogrammer.admin.controller.AdminEntitlementController;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
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
@SuppressWarnings("removal")
class AdminEntitlementTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PasswordEncoder passwordEncoder;

    @BeforeEach
    void cleanUp() {
        jdbcTemplate.update("delete from admin_audit_logs");
        jdbcTemplate.update("delete from user_module_entitlements");
        jdbcTemplate.update("delete from users");
    }

    @Test
    void entitlementEndpointsReturnDeprecatedCompatibilityResponsesWithoutWriting() throws Exception {
        insertUser("admin@example.com", "super_admin", "active");
        Long userId = insertUser("target@example.com", "user", "active");
        jdbcTemplate.update(
                "insert into user_module_entitlements (user_id, module_code, enabled, created_by, created_at, updated_by, updated_at, deleted) " +
                        "values (?, 'files', true, 0, CURRENT_TIMESTAMP, 0, CURRENT_TIMESTAMP, 0)",
                userId
        );
        Long entitlementId = jdbcTemplate.queryForObject(
                "select id from user_module_entitlements where user_id = ?", Long.class, userId);
        String token = adminAccessToken();

        mockMvc.perform(get("/api/admin/users/" + userId + "/entitlements")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));

        mockMvc.perform(post("/api/admin/users/" + userId + "/entitlements")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"moduleCode\":\"processes\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.msg").value("模块权益管理已废弃，所有登录用户均可使用服务端模块"));

        mockMvc.perform(put("/api/admin/users/" + userId + "/entitlements/" + entitlementId)
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"enabled\":false}"))
                .andExpect(status().isUnprocessableEntity());

        mockMvc.perform(delete("/api/admin/users/" + userId + "/entitlements/" + entitlementId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnprocessableEntity());

        assertEquals(1, jdbcTemplate.queryForObject(
                "select count(*) from user_module_entitlements where deleted = 0", Integer.class));
        assertEquals(0, jdbcTemplate.queryForObject(
                "select count(*) from admin_audit_logs where action like 'entitlement.%'", Integer.class));
    }

    @Test
    void legacyCommercialAdminControllersAreMarkedDeprecated() {
        assertTrue(AdminEntitlementController.class.isAnnotationPresent(Deprecated.class));
        assertTrue(AdminAnonymousDeviceController.class.isAnnotationPresent(Deprecated.class));
    }

    private String adminAccessToken() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/admin/auth/login")
                        .contentType("application/json")
                        .content("{\"identifier\":\"admin@example.com\",\"password\":\"Password123!\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return result.getResponse().getContentAsString()
                .replaceAll(".*\\\"accessToken\\\":\\\"([^\\\"]+)\\\".*", "$1");
    }

    private Long insertUser(String email, String role, String status) {
        jdbcTemplate.update(
                "insert into users (email, password_hash, role, status, email_verified, phone_verified, device_limit, offline_cache_minutes, created_by, created_at, updated_by, updated_at, deleted) " +
                        "values (?, ?, ?, ?, true, false, 1, 0, 0, CURRENT_TIMESTAMP, 0, CURRENT_TIMESTAMP, 0)",
                email, passwordEncoder.encode("Password123!"), role, status
        );
        return jdbcTemplate.queryForObject("select id from users where email = ?", Long.class, email);
    }
}
