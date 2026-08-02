package com.superprogrammer.authorization;

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

import java.time.OffsetDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestStoreConfig.class)
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:client_authorization;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ClientAuthorizationTest {

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
    void clientLoginReturns24HourAccessTokenExpiration() throws Exception {
        insertUser("user@example.com", "active", 1, 60);

        mockMvc.perform(post("/api/client/auth/login")
                        .contentType("application/json")
                        .content("{\"identifier\":\"user@example.com\",\"password\":\"Password123!\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.expiresInSeconds").value(86400));
    }

    @Test
    void activeUserWithEntitlementAndBoundDeviceGetsAuthorizationSnapshot() throws Exception {
        Long userId = insertUser("user@example.com", "active", 2, 60);
        insertDevice(userId, "device-001", "active");
        insertEntitlement(userId, "files", true, OffsetDateTime.now().plusDays(10));
        String token = userAccessToken("user@example.com");

        mockMvc.perform(get("/api/client/authorization?deviceId=device-001")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mode").value("authenticated"))
                .andExpect(jsonPath("$.data.userId").value(userId))
                .andExpect(jsonPath("$.data.accountStatus").value("active"))
                .andExpect(jsonPath("$.data.deviceLimit").value(2))
                .andExpect(jsonPath("$.data.onlineRequired").value(false))
                .andExpect(jsonPath("$.data.offlineUsableUntil").exists())
                .andExpect(jsonPath("$.data.deviceBinding.deviceId").value("device-001"))
                .andExpect(jsonPath("$.data.deviceBinding.bound").value(true))
                .andExpect(jsonPath("$.data.deviceBinding.active").value(true))
                .andExpect(jsonPath("$.data.modules[0].moduleCode").value("files"))
                .andExpect(jsonPath("$.data.modules[0].allowed").value(true))
                .andExpect(jsonPath("$.data.modules[1].moduleCode").value("processes"))
                .andExpect(jsonPath("$.data.modules[1].allowed").value(false))
                .andExpect(jsonPath("$.data.modules[2].moduleCode").value("clipboard"))
                .andExpect(jsonPath("$.data.modules[2].allowed").value(false));
    }

    @Test
    void userWithoutEntitlementsGetsAllModulesDeniedAndOnlineRequired() throws Exception {
        Long userId = insertUser("no-entitlement@example.com", "active", 1, 0);
        insertDevice(userId, "device-001", "active");
        String token = userAccessToken("no-entitlement@example.com");

        mockMvc.perform(get("/api/client/authorization?deviceId=device-001")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.onlineRequired").value(true))
                .andExpect(jsonPath("$.data.offlineUsableUntil").doesNotExist())
                .andExpect(jsonPath("$.data.modules[0].allowed").value(false))
                .andExpect(jsonPath("$.data.modules[1].allowed").value(false))
                .andExpect(jsonPath("$.data.modules[2].allowed").value(false));
    }

    @Test
    void expiredEntitlementAndDisabledDeviceDenyAllModules() throws Exception {
        Long userId = insertUser("expired@example.com", "active", 1, 0);
        insertDevice(userId, "device-001", "disabled");
        insertEntitlement(userId, "files", true, OffsetDateTime.now().minusDays(1));
        insertEntitlement(userId, "processes", true, OffsetDateTime.now().plusDays(10));
        String token = userAccessToken("expired@example.com");

        mockMvc.perform(get("/api/client/authorization?deviceId=device-001")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deviceBinding.bound").value(true))
                .andExpect(jsonPath("$.data.deviceBinding.active").value(false))
                .andExpect(jsonPath("$.data.modules[0].allowed").value(false))
                .andExpect(jsonPath("$.data.modules[1].allowed").value(false))
                .andExpect(jsonPath("$.data.modules[2].allowed").value(false));
    }

    private String userAccessToken(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/client/auth/login")
                        .contentType("application/json")
                        .content("{\"identifier\":\"" + email + "\",\"password\":\"Password123!\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return result.getResponse().getContentAsString().replaceAll(".*\\\"accessToken\\\":\\\"([^\\\"]+)\\\".*", "$1");
    }

    private Long insertUser(String email, String status, int deviceLimit, int offlineCacheMinutes) {
        jdbcTemplate.update(
                "insert into users (email, password_hash, role, status, email_verified, phone_verified, device_limit, offline_cache_minutes, created_by, created_at, updated_by, updated_at, deleted) " +
                        "values (?, ?, 'user', ?, true, false, ?, ?, 0, CURRENT_TIMESTAMP, 0, CURRENT_TIMESTAMP, 0)",
                email, passwordEncoder.encode("Password123!"), status, deviceLimit, offlineCacheMinutes
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

    private void insertEntitlement(Long userId, String moduleCode, boolean enabled, OffsetDateTime expiresAt) {
        jdbcTemplate.update(
                "insert into user_module_entitlements (user_id, module_code, enabled, expires_at, created_by, created_at, updated_by, updated_at, deleted) " +
                        "values (?, ?, ?, ?, 0, CURRENT_TIMESTAMP, 0, CURRENT_TIMESTAMP, 0)",
                userId, moduleCode, enabled, expiresAt
        );
    }
}
