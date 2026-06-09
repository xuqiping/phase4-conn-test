package com.superprogrammer.device;

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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestStoreConfig.class)
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:device_binding;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DeviceBindingTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void cleanUp() {
        jdbcTemplate.update("delete from admin_audit_logs");
        jdbcTemplate.update("delete from user_devices");
        jdbcTemplate.update("delete from users");
    }

    @Test
    void clientCanRegisterDeviceAndListAndAdminCanDisable() throws Exception {
        Long adminId = insertUser("admin@example.com", "super_admin", "active", 1);
        Long userId = insertUser("user@example.com", "user", "active", 1);
        String userToken = userAccessToken("user@example.com");
        String adminToken = adminAccessToken();

        // Register first device
        mockMvc.perform(post("/api/client/devices/register")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType("application/json")
                        .content("{\"deviceId\":\"device-001\",\"fingerprintHash\":\"hash001\",\"deviceName\":\"My Laptop\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deviceId").value("device-001"))
                .andExpect(jsonPath("$.data.deviceName").value("My Laptop"))
                .andExpect(jsonPath("$.data.status").value("active"));

        // Verify DB record
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from user_devices where user_id = ? and device_id = 'device-001' and status = 'active' and deleted = 0",
                Integer.class, userId);
        assertEquals(1, count);

        // Re-register same device updates lastSeenAt
        mockMvc.perform(post("/api/client/devices/register")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType("application/json")
                        .content("{\"deviceId\":\"device-001\",\"fingerprintHash\":\"hash001\",\"deviceName\":\"My Laptop\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deviceId").value("device-001"));

        // List own devices
        mockMvc.perform(get("/api/client/devices")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].deviceId").value("device-001"));

        // Device limit exceeded (device_limit=1)
        mockMvc.perform(post("/api/client/devices/register")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType("application/json")
                        .content("{\"deviceId\":\"device-002\",\"fingerprintHash\":\"hash002\",\"deviceName\":\"My Phone\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409));

        // Admin lists user devices
        mockMvc.perform(get("/api/admin/users/" + userId + "/devices")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));

        // Admin disables device
        mockMvc.perform(post("/api/admin/users/" + userId + "/devices/device-001/disable")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content("{\"note\":\"违规设备\"}"))
                .andExpect(status().isOk());

        // Verify device disabled
        String deviceStatus = jdbcTemplate.queryForObject(
                "select status from user_devices where device_id = 'device-001' and user_id = ?",
                String.class, userId);
        assertEquals("disabled", deviceStatus);

        // Verify audit log
        Integer auditCount = jdbcTemplate.queryForObject(
                "select count(*) from admin_audit_logs where admin_user_id = ? and action = 'device.disable'",
                Integer.class, adminId);
        assertEquals(1, auditCount);
    }

    private String adminAccessToken() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/admin/auth/login")
                        .contentType("application/json")
                        .content("{\"identifier\":\"admin@example.com\",\"password\":\"Password123!\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return result.getResponse().getContentAsString().replaceAll(".*\\\"accessToken\\\":\\\"([^\\\"]+)\\\".*", "$1");
    }

    private String userAccessToken(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/client/auth/login")
                        .contentType("application/json")
                        .content("{\"identifier\":\"" + email + "\",\"password\":\"Password123!\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return result.getResponse().getContentAsString().replaceAll(".*\\\"accessToken\\\":\\\"([^\\\"]+)\\\".*", "$1");
    }

    private Long insertUser(String email, String role, String status, int deviceLimit) {
        jdbcTemplate.update(
                "insert into users (email, password_hash, role, status, email_verified, phone_verified, device_limit, offline_cache_minutes, created_by, created_at, updated_by, updated_at, deleted) " +
                        "values (?, ?, ?, ?, true, false, ?, 0, 0, CURRENT_TIMESTAMP, 0, CURRENT_TIMESTAMP, 0)",
                email, passwordEncoder.encode("Password123!"), role, status, deviceLimit
        );
        return jdbcTemplate.queryForObject("select id from users where email = ? order by id desc limit 1", Long.class, email);
    }
}
