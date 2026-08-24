package com.superprogrammer.user;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestStoreConfig.class)
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:user_registration;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class UserRegistrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanUp() {
        jdbcTemplate.update("delete from users");
        jdbcTemplate.update("delete from system_settings");
    }

    @Test
    void registersEmailUserAfterVerification() throws Exception {
        mockMvc.perform(post("/api/client/verification/send")
                        .contentType("application/json")
                        .content("{\"contactType\":\"email\",\"contact\":\"new-user@example.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(post("/api/client/verification/check")
                        .contentType("application/json")
                        .content("{\"contactType\":\"email\",\"contact\":\"new-user@example.com\",\"code\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.verified").value(true));

        mockMvc.perform(post("/api/client/auth/register")
                        .contentType("application/json")
                        .content("{\"email\":\"new-user@example.com\",\"password\":\"Password123!\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value("new-user@example.com"))
                .andExpect(jsonPath("$.data.role").value("user"))
                .andExpect(jsonPath("$.data.status").value("active"))
                .andExpect(jsonPath("$.data.emailVerified").value(true));

        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from users where email = 'new-user@example.com' and status = 'active' and email_verified = true",
                Integer.class
        );
        assertEquals(1, count);
    }

    @Test
    void rejectsRegistrationWithoutVerifiedContact() throws Exception {
        mockMvc.perform(post("/api/client/auth/register")
                        .contentType("application/json")
                        .content("{\"email\":\"unverified@example.com\",\"password\":\"Password123!\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(422));
    }

    @Test
    void rejectsDuplicateEmailRegistration() throws Exception {
        jdbcTemplate.update(
                "insert into users (email, password_hash, role, status, email_verified, phone_verified, device_limit, offline_cache_minutes, created_by, created_at, updated_by, updated_at, deleted) " +
                        "values ('taken@example.com', 'hash', 'user', 'pending_review', true, false, 1, 0, 0, CURRENT_TIMESTAMP, 0, CURRENT_TIMESTAMP, 0)"
        );

        mockMvc.perform(post("/api/client/verification/send")
                        .contentType("application/json")
                        .content("{\"contactType\":\"email\",\"contact\":\"taken@example.com\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409));
    }

    @Test
    void registersUserWithConfiguredDefaults() throws Exception {
        jdbcTemplate.update(
                "insert into system_settings (setting_key, setting_value, description, created_by, created_at, updated_by, updated_at, deleted) " +
                        "values ('default_device_limit', '5', 'test', 0, CURRENT_TIMESTAMP, 0, CURRENT_TIMESTAMP, 0)"
        );
        jdbcTemplate.update(
                "insert into system_settings (setting_key, setting_value, description, created_by, created_at, updated_by, updated_at, deleted) " +
                        "values ('default_offline_cache_minutes', '90', 'test', 0, CURRENT_TIMESTAMP, 0, CURRENT_TIMESTAMP, 0)"
        );

        mockMvc.perform(post("/api/client/verification/send")
                        .contentType("application/json")
                        .content("{\"contactType\":\"email\",\"contact\":\"configured@example.com\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/client/verification/check")
                        .contentType("application/json")
                        .content("{\"contactType\":\"email\",\"contact\":\"configured@example.com\",\"code\":\"123456\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/client/auth/register")
                        .contentType("application/json")
                        .content("{\"email\":\"configured@example.com\",\"password\":\"Password123!\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deviceLimit").value(5))
                .andExpect(jsonPath("$.data.offlineCacheMinutes").value(90));
    }
}
