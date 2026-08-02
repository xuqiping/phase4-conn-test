package com.superprogrammer.admin;

import com.superprogrammer.admin.service.AdminUserService;
import com.superprogrammer.support.TestStoreConfig;
import com.superprogrammer.user.dto.UserSettingsUpdateRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestStoreConfig.class)
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:admin_user_settings;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AdminUserSettingsTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private AdminUserService adminUserService;

    @BeforeEach
    void cleanUp() {
        jdbcTemplate.update("delete from admin_audit_logs");
        jdbcTemplate.update("delete from users");
    }

    @Test
    void adminCanUpdateUserDeviceLimitAndOfflineCacheSettings() throws Exception {
        Long adminId = insertUser("admin@example.com", "super_admin", "active");
        Long userId = insertUser("target@example.com", "user", "active");
        String accessToken = adminAccessToken();

        mockMvc.perform(put("/api/admin/users/" + userId + "/settings")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType("application/json")
                        .content("{\"deviceLimit\":3,\"offlineCacheMinutes\":60}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deviceLimit").value(3))
                .andExpect(jsonPath("$.data.offlineCacheMinutes").value(60));

        Integer deviceLimit = jdbcTemplate.queryForObject("select device_limit from users where id = ?", Integer.class, userId);
        Integer offlineCacheMinutes = jdbcTemplate.queryForObject("select offline_cache_minutes from users where id = ?", Integer.class, userId);
        assertEquals(3, deviceLimit);
        assertEquals(60, offlineCacheMinutes);

        Integer auditCount = jdbcTemplate.queryForObject(
                "select count(*) from admin_audit_logs where admin_user_id = ? and action = 'user.update_settings' and target_type = 'user' and target_id = ?",
                Integer.class,
                adminId,
                String.valueOf(userId)
        );
        assertEquals(1, auditCount);
    }

    @Test
    void rejectInvalidUserSettingsValues() throws Exception {
        insertUser("admin@example.com", "super_admin", "active");
        Long userId = insertUser("target@example.com", "user", "active");
        String accessToken = adminAccessToken();

        mockMvc.perform(put("/api/admin/users/" + userId + "/settings")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType("application/json")
                        .content("{\"deviceLimit\":0,\"offlineCacheMinutes\":60}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        mockMvc.perform(put("/api/admin/users/" + userId + "/settings")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType("application/json")
                        .content("{\"deviceLimit\":3,\"offlineCacheMinutes\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void rejectSettingsUpdateForMissingUser() throws Exception {
        insertUser("admin@example.com", "super_admin", "active");
        String accessToken = adminAccessToken();

        mockMvc.perform(put("/api/admin/users/999999/settings")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType("application/json")
                        .content("{\"deviceLimit\":3,\"offlineCacheMinutes\":60}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    void rollbackSettingsUpdateWhenAuditLogFails() {
        Long userId = insertUser("target@example.com", "user", "active");

        assertThrows(DataIntegrityViolationException.class,
                () -> adminUserService.updateSettings(999999L, userId, new UserSettingsUpdateRequest(3, 60)));

        Integer deviceLimit = jdbcTemplate.queryForObject("select device_limit from users where id = ?", Integer.class, userId);
        Integer offlineCacheMinutes = jdbcTemplate.queryForObject("select offline_cache_minutes from users where id = ?", Integer.class, userId);
        assertEquals(1, deviceLimit);
        assertEquals(0, offlineCacheMinutes);
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
