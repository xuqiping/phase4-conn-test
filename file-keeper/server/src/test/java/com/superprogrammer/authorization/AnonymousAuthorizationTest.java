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
    void anonymousSnapshotAlwaysAllowsLocalModulesAndRequiresLoginForServerModules() throws Exception {
        insertDisabledLegacyTrial();

        mockMvc.perform(get("/api/anonymous/authorization?deviceId=legacy-device&fingerprintHash=any-fingerprint"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mode").value("anonymous"))
                .andExpect(jsonPath("$.data.onlineRequired").value(true))
                .andExpect(jsonPath("$.data.modules[0].moduleCode").value("files"))
                .andExpect(jsonPath("$.data.modules[0].allowed").value(true))
                .andExpect(jsonPath("$.data.modules[1].allowed").value(true))
                .andExpect(jsonPath("$.data.modules[2].allowed").value(true))
                .andExpect(jsonPath("$.data.modules[3].allowed").value(false))
                .andExpect(jsonPath("$.data.modules[3].reason").value("请先登录"))
                .andExpect(jsonPath("$.data.modules[4].allowed").value(false))
                .andExpect(jsonPath("$.data.modules[4].reason").value("请先登录"));
    }

    private void insertDisabledLegacyTrial() {
        jdbcTemplate.update(
                "insert into anonymous_device_trials (device_id, fingerprint_hash, device_name, trial_started_at, trial_expires_at, status, created_by, created_at, updated_by, updated_at, deleted) " +
                        "values ('legacy-device', 'legacy-fingerprint', 'Laptop', ?, ?, 'disabled', 0, CURRENT_TIMESTAMP, 0, CURRENT_TIMESTAMP, 0)",
                OffsetDateTime.now().minusDays(10), OffsetDateTime.now().minusDays(1)
        );
    }
}
