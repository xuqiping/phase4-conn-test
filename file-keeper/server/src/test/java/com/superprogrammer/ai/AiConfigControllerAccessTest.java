package com.superprogrammer.ai;

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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestStoreConfig.class)
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:ai_config_access;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AiConfigControllerAccessTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void cleanUp() {
        jdbcTemplate.update("delete from ai_configs");
        jdbcTemplate.update("delete from user_devices");
        jdbcTemplate.update("delete from user_module_entitlements");
        jdbcTemplate.update("delete from users");
    }

    @Test
    void activeUserWithoutEntitlementCanListAiConfigs() throws Exception {
        Long userId = insertUser("ai-open-access@example.com");
        insertDevice(userId, "ai-device", "active");
        String token = userAccessToken("ai-open-access@example.com");

        mockMvc.perform(get("/api/client/ai-configs")
                        .param("deviceId", "ai-device")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void disabledDeviceCannotListAiConfigs() throws Exception {
        Long userId = insertUser("ai-disabled-device@example.com");
        insertDevice(userId, "ai-device", "disabled");
        String token = userAccessToken("ai-disabled-device@example.com");

        mockMvc.perform(get("/api/client/ai-configs")
                        .param("deviceId", "ai-device")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void userCannotReadAnotherUsersAiConfig() throws Exception {
        Long userId = insertUser("ai-owner@example.com");
        Long otherUserId = insertUser("ai-other@example.com");
        insertDevice(userId, "ai-device", "active");
        Long otherConfigId = insertAiConfig(otherUserId);
        String token = userAccessToken("ai-owner@example.com");

        mockMvc.perform(get("/api/client/ai-configs/" + otherConfigId)
                        .param("deviceId", "ai-device")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    void missingJwtCannotAccessAiConfigs() throws Exception {
        mockMvc.perform(get("/api/client/ai-configs")
                        .param("deviceId", "ai-device"))
                .andExpect(status().isUnauthorized());
    }

    private Long insertUser(String email) {
        jdbcTemplate.update(
                "insert into users (email, password_hash, role, status, email_verified, phone_verified, device_limit, offline_cache_minutes, created_by, created_at, updated_by, updated_at, deleted) " +
                        "values (?, ?, 'user', 'active', true, false, 1, 0, 0, CURRENT_TIMESTAMP, 0, CURRENT_TIMESTAMP, 0)",
                email, passwordEncoder.encode("Password123!")
        );
        return jdbcTemplate.queryForObject("select id from users where email = ?", Long.class, email);
    }

    private void insertDevice(Long userId, String deviceId, String status) {
        jdbcTemplate.update(
                "insert into user_devices (user_id, device_id, fingerprint_hash, device_name, status, last_seen_at, created_by, created_at, updated_by, updated_at, deleted) " +
                        "values (?, ?, 'fp', 'Laptop', ?, CURRENT_TIMESTAMP, 0, CURRENT_TIMESTAMP, 0, CURRENT_TIMESTAMP, 0)",
                userId, deviceId, status
        );
    }

    private Long insertAiConfig(Long userId) {
        jdbcTemplate.update(
                "insert into ai_configs (user_id, name, provider, model, max_tokens, timeout_seconds, is_default, enabled, created_by, created_at, updated_by, updated_at, deleted) " +
                        "values (?, 'Other config', 'openai', 'gpt-test', 1024, 30, false, true, ?, CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP, 0)",
                userId, userId, userId
        );
        return jdbcTemplate.queryForObject(
                "select id from ai_configs where user_id = ? order by id desc limit 1", Long.class, userId);
    }

    private String userAccessToken(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/client/auth/login")
                        .contentType("application/json")
                        .content("{\"identifier\":\"" + email + "\",\"password\":\"Password123!\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return result.getResponse().getContentAsString()
                .replaceAll(".*\\\"accessToken\\\":\\\"([^\\\"]+)\\\".*", "$1");
    }
}
