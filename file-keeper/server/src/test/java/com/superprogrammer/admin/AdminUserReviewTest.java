package com.superprogrammer.admin;

import com.superprogrammer.support.TestStoreConfig;
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
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:admin_user_review;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AdminUserReviewTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void adminCanListDetailAndApprovePendingUser() throws Exception {
        Long adminId = insertUser("admin@example.com", "super_admin", "active");
        Long userId = insertUser("pending@example.com", "user", "pending_review");
        String accessToken = adminAccessToken();

        // List pending users
        mockMvc.perform(get("/api/admin/users?status=pending_review&page=1&size=20")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].email").value("pending@example.com"))
                .andExpect(jsonPath("$.data.total").value(1));

        // Get user detail
        mockMvc.perform(get("/api/admin/users/" + userId)
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("pending_review"));

        // Approve the pending user
        mockMvc.perform(post("/api/admin/users/" + userId + "/approve")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType("application/json")
                        .content("{\"note\":\"资料确认通过\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("active"));

        // Verify DB status
        String status = jdbcTemplate.queryForObject("select status from users where id = ?", String.class, userId);
        assertEquals("active", status);

        // Verify audit log
        Integer auditCount = jdbcTemplate.queryForObject(
                "select count(*) from admin_audit_logs where admin_user_id = ? and action = 'user.approve' and target_type = 'user' and target_id = ?",
                Integer.class,
                adminId,
                String.valueOf(userId)
        );
        assertEquals(1, auditCount);
    }

    private String adminAccessToken() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/admin/auth/login")
                        .contentType("application/json")
                        .content("{\"identifier\":\"admin@example.com\",\"password\":\"AdminPass123!\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return result.getResponse().getContentAsString().replaceAll(".*\\\"accessToken\\\":\\\"([^\\\"]+)\\\".*", "$1");
    }

    private Long insertUser(String email, String role, String status) {
        jdbcTemplate.update(
                "insert into users (email, password_hash, role, status, email_verified, phone_verified, device_limit, offline_cache_minutes, created_by, created_at, updated_by, updated_at, deleted) " +
                        "values (?, ?, ?, ?, true, false, 1, 0, 0, CURRENT_TIMESTAMP, 0, CURRENT_TIMESTAMP, 0)",
                email,
                passwordEncoder.encode("AdminPass123!"),
                role,
                status
        );
        return jdbcTemplate.queryForObject("select id from users where email = ? order by id desc limit 1", Long.class, email);
    }
}
