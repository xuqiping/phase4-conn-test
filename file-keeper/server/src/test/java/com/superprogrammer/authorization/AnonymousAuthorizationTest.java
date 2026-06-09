package com.superprogrammer.authorization;

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

import java.time.OffsetDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestStoreConfig.class)
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:anonymous_authorization;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AnonymousAuthorizationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanUp() {
        jdbcTemplate.update("delete from anonymous_device_trials");
    }

    @Test
    void deviceWithoutTrialGetsAllModulesDenied() throws Exception {
        mockMvc.perform(get("/api/anonymous/authorization?deviceId=anon-missing&fingerprintHash=fp-missing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mode").value("anonymous"))
                .andExpect(jsonPath("$.data.onlineRequired").value(true))
                .andExpect(jsonPath("$.data.modules[0].allowed").value(false))
                .andExpect(jsonPath("$.data.modules[0].reason").value("未开始试用"))
                .andExpect(jsonPath("$.data.modules[1].allowed").value(false))
                .andExpect(jsonPath("$.data.modules[2].allowed").value(false));
    }

    @Test
    void fullTrialAllowsAllModules() throws Exception {
        insertTrial("anon-001", "fp-001", OffsetDateTime.now().plusDays(6), null, "active");

        mockMvc.perform(get("/api/anonymous/authorization?deviceId=anon-001&fingerprintHash=fp-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mode").value("anonymous"))
                .andExpect(jsonPath("$.data.onlineRequired").value(true))
                .andExpect(jsonPath("$.data.modules[0].moduleCode").value("files"))
                .andExpect(jsonPath("$.data.modules[0].allowed").value(true))
                .andExpect(jsonPath("$.data.modules[1].moduleCode").value("processes"))
                .andExpect(jsonPath("$.data.modules[1].allowed").value(true))
                .andExpect(jsonPath("$.data.modules[2].moduleCode").value("clipboard"))
                .andExpect(jsonPath("$.data.modules[2].allowed").value(true));
    }

    @Test
    void expiredTrialWithFreeModuleAllowsOnlySelectedModule() throws Exception {
        insertTrial("anon-002", "fp-002", OffsetDateTime.now().minusDays(1), "clipboard", "active");

        mockMvc.perform(get("/api/anonymous/authorization?deviceId=anon-002&fingerprintHash=fp-002"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.modules[0].moduleCode").value("files"))
                .andExpect(jsonPath("$.data.modules[0].allowed").value(false))
                .andExpect(jsonPath("$.data.modules[1].moduleCode").value("processes"))
                .andExpect(jsonPath("$.data.modules[1].allowed").value(false))
                .andExpect(jsonPath("$.data.modules[2].moduleCode").value("clipboard"))
                .andExpect(jsonPath("$.data.modules[2].allowed").value(true));
    }

    @Test
    void expiredTrialWithoutFreeModuleAndDisabledTrialDenyAllModules() throws Exception {
        insertTrial("anon-003", "fp-003", OffsetDateTime.now().minusDays(1), null, "active");

        mockMvc.perform(get("/api/anonymous/authorization?deviceId=anon-003&fingerprintHash=fp-003"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.modules[0].allowed").value(false))
                .andExpect(jsonPath("$.data.modules[0].reason").value("试用期已结束，请选择免费模块"))
                .andExpect(jsonPath("$.data.modules[1].allowed").value(false))
                .andExpect(jsonPath("$.data.modules[2].allowed").value(false));

        jdbcTemplate.update("update anonymous_device_trials set status = 'disabled' where device_id = 'anon-003'");

        mockMvc.perform(get("/api/anonymous/authorization?deviceId=anon-003&fingerprintHash=fp-003"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.modules[0].allowed").value(false))
                .andExpect(jsonPath("$.data.modules[0].reason").value("匿名设备已禁用"))
                .andExpect(jsonPath("$.data.modules[1].allowed").value(false))
                .andExpect(jsonPath("$.data.modules[2].allowed").value(false));
    }

    @Test
    void fingerprintMismatchDeniesAllModules() throws Exception {
        insertTrial("anon-004", "fp-004", OffsetDateTime.now().plusDays(6), null, "active");

        mockMvc.perform(get("/api/anonymous/authorization?deviceId=anon-004&fingerprintHash=wrong"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.modules[0].allowed").value(false))
                .andExpect(jsonPath("$.data.modules[0].reason").value("设备指纹不匹配"))
                .andExpect(jsonPath("$.data.modules[1].allowed").value(false))
                .andExpect(jsonPath("$.data.modules[2].allowed").value(false));
    }

    private void insertTrial(String deviceId, String fingerprintHash, OffsetDateTime trialExpiresAt, String freeModuleCode, String status) {
        jdbcTemplate.update(
                "insert into anonymous_device_trials (device_id, fingerprint_hash, device_name, trial_started_at, trial_expires_at, free_module_code, free_module_selected_at, last_free_module_changed_at, status, created_by, created_at, updated_by, updated_at, deleted) " +
                        "values (?, ?, 'Laptop', ?, ?, ?, ?, ?, ?, 0, CURRENT_TIMESTAMP, 0, CURRENT_TIMESTAMP, 0)",
                deviceId,
                fingerprintHash,
                OffsetDateTime.now().minusDays(8),
                trialExpiresAt,
                freeModuleCode,
                freeModuleCode != null ? OffsetDateTime.now().minusDays(1) : null,
                freeModuleCode != null ? OffsetDateTime.now().minusDays(1) : null,
                status
        );
    }
}
