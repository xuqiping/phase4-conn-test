package com.superprogrammer.admin;

import com.superprogrammer.admin.controller.AdminSettingsController;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestStoreConfig.class)
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:admin_settings;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SuppressWarnings("removal")
class AdminSettingsTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PasswordEncoder passwordEncoder;

    @BeforeEach
    void cleanUp() {
        jdbcTemplate.update("delete from admin_audit_logs");
        jdbcTemplate.update("delete from system_settings");
        jdbcTemplate.update("delete from users");
    }

    @Test
    void commercialSettingsUseFixedCompatibilityReadAndRejectWrites() throws Exception {
        insertSuperAdmin();
        jdbcTemplate.update(
                "insert into system_settings (setting_key, setting_value, description, created_by, created_at, updated_by, updated_at, deleted) " +
                        "values ('default_device_limit', '99', 'legacy', 0, CURRENT_TIMESTAMP, 0, CURRENT_TIMESTAMP, 0)"
        );
        String token = adminAccessToken();

        mockMvc.perform(get("/api/admin/settings").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.defaultDeviceLimit").value(1))
                .andExpect(jsonPath("$.data.defaultOfflineCacheMinutes").value(0))
                .andExpect(jsonPath("$.data.anonymousTrialDays").value(7))
                .andExpect(jsonPath("$.data.freeModuleChangeDays").value(30));

        mockMvc.perform(put("/api/admin/settings")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"defaultDeviceLimit\":3,\"defaultOfflineCacheMinutes\":60,\"anonymousTrialDays\":14,\"freeModuleChangeDays\":15}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.msg").value("商业化系统设置已废弃"));

        assertEquals("99", jdbcTemplate.queryForObject(
                "select setting_value from system_settings where setting_key = 'default_device_limit'", String.class));
        assertEquals(0, jdbcTemplate.queryForObject(
                "select count(*) from admin_audit_logs where action = 'system.update_settings'", Integer.class));
    }

    @Test
    void commercialSettingsControllerIsMarkedDeprecated() {
        assertTrue(AdminSettingsController.class.isAnnotationPresent(Deprecated.class));
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

    private void insertSuperAdmin() {
        jdbcTemplate.update(
                "insert into users (email, password_hash, role, status, email_verified, phone_verified, device_limit, offline_cache_minutes, created_by, created_at, updated_by, updated_at, deleted) " +
                        "values (?, ?, 'super_admin', 'active', true, false, 1, 0, 0, CURRENT_TIMESTAMP, 0, CURRENT_TIMESTAMP, 0)",
                "admin@example.com", passwordEncoder.encode("Password123!")
        );
    }
}
