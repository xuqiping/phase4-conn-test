package com.superprogrammer.device;

import com.superprogrammer.support.TestStoreConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestStoreConfig.class)
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:anonymous_trial;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AnonymousTrialTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void cleanUp() {
        jdbcTemplate.update("delete from anonymous_device_trials");
        jdbcTemplate.update("delete from system_settings");
        redisTemplate.delete(redisTemplate.keys("anon:start:fp:*"));
        redisTemplate.delete(redisTemplate.keys("anon:start:ip:*"));
    }

    @Test
    void startTrialCreatesSevenDayFullAccessAndIsIdempotent() throws Exception {
        mockMvc.perform(post("/api/anonymous/trial/start")
                        .contentType("application/json")
                        .content("{\"deviceId\":\"anon-001\",\"fingerprintHash\":\"fp-001\",\"deviceName\":\"Laptop\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deviceId").value("anon-001"))
                .andExpect(jsonPath("$.data.inFullTrial").value(true))
                .andExpect(jsonPath("$.data.trialExpired").value(false))
                .andExpect(jsonPath("$.data.allowedModuleCodes.length()").value(5))
                .andExpect(jsonPath("$.data.allowedModuleCodes[0]").value("files"))
                .andExpect(jsonPath("$.data.allowedModuleCodes[1]").value("processes"))
                .andExpect(jsonPath("$.data.allowedModuleCodes[2]").value("clipboard"))
                .andExpect(jsonPath("$.data.allowedModuleCodes[3]").value("work-report"))
                .andExpect(jsonPath("$.data.allowedModuleCodes[4]").value("ai"));

        mockMvc.perform(post("/api/anonymous/trial/start")
                        .contentType("application/json")
                        .content("{\"deviceId\":\"anon-001\",\"fingerprintHash\":\"fp-001\",\"deviceName\":\"Laptop\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deviceId").value("anon-001"));

        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from anonymous_device_trials where device_id = 'anon-001' and deleted = 0",
                Integer.class);
        assertEquals(1, count);
    }

    @Test
    void expiredTrialCanSelectAndChangeFreeModuleAfterThirtyDays() throws Exception {
        insertTrial("anon-002", "fp-002", "Laptop", OffsetDateTime.now().minusDays(8), null, null, "active");

        mockMvc.perform(get("/api/anonymous/trial/status?deviceId=anon-002&fingerprintHash=fp-002"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.inFullTrial").value(false))
                .andExpect(jsonPath("$.data.trialExpired").value(true))
                .andExpect(jsonPath("$.data.allowedModuleCodes.length()").value(0));

        mockMvc.perform(post("/api/anonymous/trial/select-free-module")
                        .contentType("application/json")
                        .content("{\"deviceId\":\"anon-002\",\"fingerprintHash\":\"fp-002\",\"freeModuleCode\":\"files\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.freeModuleCode").value("files"))
                .andExpect(jsonPath("$.data.allowedModuleCodes.length()").value(1))
                .andExpect(jsonPath("$.data.allowedModuleCodes[0]").value("files"));

        jdbcTemplate.update(
                "update anonymous_device_trials set last_free_module_changed_at = ? where device_id = 'anon-002'",
                OffsetDateTime.now().minusDays(31)
        );

        mockMvc.perform(post("/api/anonymous/trial/change-free-module")
                        .contentType("application/json")
                        .content("{\"deviceId\":\"anon-002\",\"fingerprintHash\":\"fp-002\",\"freeModuleCode\":\"clipboard\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.freeModuleCode").value("clipboard"))
                .andExpect(jsonPath("$.data.allowedModuleCodes[0]").value("clipboard"));
    }

    @Test
    void rejectFreeModuleSelectionDuringTrialAndMonthlyChangeTooSoon() throws Exception {
        insertTrial("anon-003", "fp-003", "Laptop", OffsetDateTime.now().plusDays(6), "files", OffsetDateTime.now().minusDays(10), "active");

        mockMvc.perform(post("/api/anonymous/trial/select-free-module")
                        .contentType("application/json")
                        .content("{\"deviceId\":\"anon-003\",\"fingerprintHash\":\"fp-003\",\"freeModuleCode\":\"files\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(422));

        jdbcTemplate.update(
                "update anonymous_device_trials set trial_expires_at = ?, free_module_code = 'files', last_free_module_changed_at = ? where device_id = 'anon-003'",
                OffsetDateTime.now().minusDays(1),
                OffsetDateTime.now().minusDays(10)
        );

        mockMvc.perform(post("/api/anonymous/trial/change-free-module")
                        .contentType("application/json")
                        .content("{\"deviceId\":\"anon-003\",\"fingerprintHash\":\"fp-003\",\"freeModuleCode\":\"processes\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(422));
    }

    @Test
    void rejectFingerprintMismatchAndDisabledTrial() throws Exception {
        insertTrial("anon-004", "fp-004", "Laptop", OffsetDateTime.now().minusDays(1), null, null, "active");

        mockMvc.perform(get("/api/anonymous/trial/status?deviceId=anon-004&fingerprintHash=wrong"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));

        jdbcTemplate.update("update anonymous_device_trials set status = 'disabled' where device_id = 'anon-004'");

        mockMvc.perform(get("/api/anonymous/trial/status?deviceId=anon-004&fingerprintHash=fp-004"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void trialDurationFollowsConfiguredDays() throws Exception {
        jdbcTemplate.update(
                "insert into system_settings (setting_key, setting_value, description, created_by, created_at, updated_by, updated_at, deleted) " +
                        "values ('anonymous_trial_days', '3', 'test', 0, CURRENT_TIMESTAMP, 0, CURRENT_TIMESTAMP, 0)"
        );

        mockMvc.perform(post("/api/anonymous/trial/start")
                        .contentType("application/json")
                        .content("{\"deviceId\":\"anon-cfg-001\",\"fingerprintHash\":\"fp-cfg-001\",\"deviceName\":\"Laptop\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.inFullTrial").value(true));

        OffsetDateTime expiresAt = jdbcTemplate.queryForObject(
                "select trial_expires_at from anonymous_device_trials where device_id = 'anon-cfg-001'", OffsetDateTime.class);
        OffsetDateTime startedAt = jdbcTemplate.queryForObject(
                "select trial_started_at from anonymous_device_trials where device_id = 'anon-cfg-001'", OffsetDateTime.class);
        long daysBetween = java.time.Duration.between(startedAt, expiresAt).toDays();
        assertEquals(3, daysBetween);
    }

    @Test
    void freeModuleChangeIntervalFollowsConfiguredDays() throws Exception {
        jdbcTemplate.update(
                "insert into system_settings (setting_key, setting_value, description, created_by, created_at, updated_by, updated_at, deleted) " +
                        "values ('free_module_change_days', '10', 'test', 0, CURRENT_TIMESTAMP, 0, CURRENT_TIMESTAMP, 0)"
        );

        // 过期试用 + 已选免费模块，上次更换在 11 天前（超过配置的 10 天间隔）→ 允许更换
        insertTrial("anon-cfg-002", "fp-cfg-002", "Laptop", OffsetDateTime.now().minusDays(1), "files", OffsetDateTime.now().minusDays(11), "active");
        jdbcTemplate.update(
                "update anonymous_device_trials set last_free_module_changed_at = ? where device_id = 'anon-cfg-002'",
                OffsetDateTime.now().minusDays(11)
        );

        mockMvc.perform(post("/api/anonymous/trial/change-free-module")
                        .contentType("application/json")
                        .content("{\"deviceId\":\"anon-cfg-002\",\"fingerprintHash\":\"fp-cfg-002\",\"freeModuleCode\":\"clipboard\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.freeModuleCode").value("clipboard"));

        // 新设备，上次更换在 9 天前（未超过配置的 10 天间隔）→ 拒绝
        insertTrial("anon-cfg-003", "fp-cfg-003", "Laptop", OffsetDateTime.now().minusDays(1), "files", OffsetDateTime.now().minusDays(9), "active");
        jdbcTemplate.update(
                "update anonymous_device_trials set last_free_module_changed_at = ? where device_id = 'anon-cfg-003'",
                OffsetDateTime.now().minusDays(9)
        );

        mockMvc.perform(post("/api/anonymous/trial/change-free-module")
                        .contentType("application/json")
                        .content("{\"deviceId\":\"anon-cfg-003\",\"fingerprintHash\":\"fp-cfg-003\",\"freeModuleCode\":\"clipboard\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(422));
    }

    private void insertTrial(String deviceId, String fingerprintHash, String deviceName, OffsetDateTime trialExpiresAt,
                             String freeModuleCode, OffsetDateTime lastFreeModuleChangedAt, String status) {
        jdbcTemplate.update(
                "insert into anonymous_device_trials (device_id, fingerprint_hash, device_name, trial_started_at, trial_expires_at, free_module_code, free_module_selected_at, last_free_module_changed_at, status, created_by, created_at, updated_by, updated_at, deleted) " +
                        "values (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, CURRENT_TIMESTAMP, 0, CURRENT_TIMESTAMP, 0)",
                deviceId,
                fingerprintHash,
                deviceName,
                OffsetDateTime.now().minusDays(8),
                trialExpiresAt,
                freeModuleCode,
                freeModuleCode != null ? OffsetDateTime.now().minusDays(1) : null,
                lastFreeModuleChangedAt,
                status
        );
    }
}
