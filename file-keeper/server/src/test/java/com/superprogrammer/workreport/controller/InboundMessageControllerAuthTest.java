package com.superprogrammer.workreport.controller;

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
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:inbound_message_auth;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class InboundMessageControllerAuthTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void cleanUp() {
        jdbcTemplate.update("delete from user_devices");
        jdbcTemplate.update("delete from user_module_entitlements");
        jdbcTemplate.update("delete from users");
    }

    @Test
    void activeUserWithoutEntitlementCanListInbox() throws Exception {
        Long userId = insertUser("inbox-open-access@example.com", "active");
        insertDevice(userId, "test-device", "active");
        String token = userAccessToken("inbox-open-access@example.com");

        mockMvc.perform(get("/api/client/work-report/inbox")
                        .param("deviceId", "test-device")
                        .param("limit", "50")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void disabledDeviceCannotConfirmInboxMessage() throws Exception {
        Long userId = insertUser("inbox-disabled-device@example.com", "active");
        insertDevice(userId, "test-device", "disabled");
        String token = userAccessToken("inbox-disabled-device@example.com");

        mockMvc.perform(post("/api/client/work-report/inbox/1/confirm")
                        .param("deviceId", "test-device")
                        .contentType("application/json")
                        .content("{\"action\":\"CONFIRM\"}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    private String userAccessToken(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/client/auth/login")
                        .contentType("application/json")
                        .content("{\"identifier\":\"" + email + "\",\"password\":\"Password123!\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return result.getResponse().getContentAsString().replaceAll(".*\\\"accessToken\\\":\\\"([^\\\"]+)\\\".*", "$1");
    }

    private Long insertUser(String email, String status) {
        jdbcTemplate.update(
                "insert into users (email, password_hash, role, status, email_verified, phone_verified, device_limit, offline_cache_minutes, created_by, created_at, updated_by, updated_at, deleted) " +
                        "values (?, ?, 'user', ?, true, false, 2, 0, 0, CURRENT_TIMESTAMP, 0, CURRENT_TIMESTAMP, 0)",
                email, passwordEncoder.encode("Password123!"), status
        );
        return jdbcTemplate.queryForObject("select id from users where email = ? order by id desc limit 1", Long.class, email);
    }

    private void insertDevice(Long userId, String deviceId, String status) {
        jdbcTemplate.update(
                "insert into user_devices (user_id, device_id, fingerprint_hash, device_name, status, last_seen_at, created_by, created_at, updated_by, updated_at, deleted) " +
                        "values (?, ?, 'fp', 'Laptop', ?, CURRENT_TIMESTAMP, 0, CURRENT_TIMESTAMP, 0, CURRENT_TIMESTAMP, 0)",
                userId, deviceId, status
        );
    }

}
