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

    @BeforeEach
    void cleanUp() {
        jdbcTemplate.update("delete from anonymous_device_trials");
    }

    @Test
    void startReturnsLocalAccessCompatibilityWithoutCreatingTrial() throws Exception {
        mockMvc.perform(post("/api/anonymous/trial/start")
                        .contentType("application/json")
                        .content("{\"deviceId\":\"anon-001\",\"fingerprintHash\":\"fp-001\",\"deviceName\":\"Laptop\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deviceId").value("anon-001"))
                .andExpect(jsonPath("$.data.inFullTrial").value(false))
                .andExpect(jsonPath("$.data.trialExpired").value(true))
                .andExpect(jsonPath("$.data.allowedModuleCodes.length()").value(3))
                .andExpect(jsonPath("$.data.allowedModuleCodes[0]").value("files"))
                .andExpect(jsonPath("$.data.allowedModuleCodes[1]").value("processes"))
                .andExpect(jsonPath("$.data.allowedModuleCodes[2]").value("clipboard"));

        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from anonymous_device_trials", Integer.class);
        assertEquals(0, count);
    }

    @Test
    void statusDoesNotRequireLegacyTrialRecord() throws Exception {
        mockMvc.perform(get("/api/anonymous/trial/status?deviceId=anon-missing&fingerprintHash=fp-missing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deviceId").value("anon-missing"))
                .andExpect(jsonPath("$.data.allowedModuleCodes.length()").value(3));
    }

    @Test
    void freeModuleSelectionEndpointsReturnDeprecatedResponse() throws Exception {
        String requestBody = "{\"deviceId\":\"anon-001\",\"fingerprintHash\":\"fp-001\",\"freeModuleCode\":\"files\"}";

        mockMvc.perform(post("/api/anonymous/trial/select-free-module")
                        .contentType("application/json")
                        .content(requestBody))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(422));

        mockMvc.perform(post("/api/anonymous/trial/change-free-module")
                        .contentType("application/json")
                        .content(requestBody))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(422));
    }
}
