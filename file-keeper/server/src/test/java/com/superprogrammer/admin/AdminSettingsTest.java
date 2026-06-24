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
class AdminSettingsTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void cleanUp() {
        jdbcTemplate.update("delete from admin_audit_logs");
        jdbcTemplate.update("delete from system_settings");
        jdbcTemplate.update("delete from users");
    }

    @Test
    void getReturnsDefaultsWhenTableEmpty() throws Exception {
        insertSuperAdmin();
        String accessToken = adminAccessToken();

        mockMvc.perform(get("/api/admin/settings").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.defaultDeviceLimit").value(1))
                .andExpect(jsonPath("$.data.defaultOfflineCacheMinutes").value(0))
                .andExpect(jsonPath("$.data.anonymousTrialDays").value(7))
                .andExpect(jsonPath("$.data.freeModuleChangeDays").value(30));
    }

    @Test
    void putUpdatesAndPersists() throws Exception {
        insertSuperAdmin();
        String accessToken = adminAccessToken();

        mockMvc.perform(put("/api/admin/settings")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType("application/json")
                        .content("{\"defaultDeviceLimit\":3,\"defaultOfflineCacheMinutes\":60,\"anonymousTrialDays\":14,\"freeModuleChangeDays\":15}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.defaultDeviceLimit").value(3))
                .andExpect(jsonPath("$.data.anonymousTrialDays").value(14));

        Integer count = jdbcTemplate.queryForObject("select count(*) from system_settings where deleted = 0", Integer.class);
        assertEquals(4, count);

        // GET 返回新值
        mockMvc.perform(get("/api/admin/settings").header("Authorization", "Bearer " + accessToken))
                .andExpect(jsonPath("$.data.defaultDeviceLimit").value(3))
                .andExpect(jsonPath("$.data.defaultOfflineCacheMinutes").value(60))
                .andExpect(jsonPath("$.data.anonymousTrialDays").value(14))
                .andExpect(jsonPath("$.data.freeModuleChangeDays").value(15));

        // 再次 PUT 是 update（验证两步法 upsert 不重复插入）
        mockMvc.perform(put("/api/admin/settings")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType("application/json")
                        .content("{\"defaultDeviceLimit\":5,\"defaultOfflineCacheMinutes\":10,\"anonymousTrialDays\":3,\"freeModuleChangeDays\":20}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.defaultDeviceLimit").value(5));

        Integer countAfterUpdate = jdbcTemplate.queryForObject("select count(*) from system_settings where deleted = 0", Integer.class);
        assertEquals(4, countAfterUpdate);
    }

    @Test
    void rejectInvalidValues() throws Exception {
        insertSuperAdmin();
        String accessToken = adminAccessToken();

        mockMvc.perform(put("/api/admin/settings")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType("application/json")
                        .content("{\"defaultDeviceLimit\":0,\"defaultOfflineCacheMinutes\":60,\"anonymousTrialDays\":14,\"freeModuleChangeDays\":15}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put("/api/admin/settings")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType("application/json")
                        .content("{\"defaultDeviceLimit\":3,\"defaultOfflineCacheMinutes\":60,\"anonymousTrialDays\":0,\"freeModuleChangeDays\":15}"))
                .andExpect(status().isBadRequest());
    }

    private String adminAccessToken() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/admin/auth/login")
                        .contentType("application/json")
                        .content("{\"identifier\":\"admin@example.com\",\"password\":\"Password123!\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return result.getResponse().getContentAsString().replaceAll(".*\\\"accessToken\\\":\\\"([^\\\"]+)\\\".*", "$1");
    }

    private void insertSuperAdmin() {
        jdbcTemplate.update(
                "insert into users (email, password_hash, role, status, email_verified, phone_verified, device_limit, offline_cache_minutes, created_by, created_at, updated_by, updated_at, deleted) "
                        + "values (?, ?, 'super_admin', 'active', true, false, 1, 0, 0, CURRENT_TIMESTAMP, 0, CURRENT_TIMESTAMP, 0)",
                "admin@example.com", passwordEncoder.encode("Password123!")
        );
    }
}
