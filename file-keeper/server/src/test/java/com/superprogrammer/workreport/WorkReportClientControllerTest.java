package com.superprogrammer.workreport;

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

import java.time.LocalDate;
import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestStoreConfig.class)
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:work_report_client;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WorkReportClientControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void cleanUp() {
        jdbcTemplate.update("delete from push_deliveries");
        jdbcTemplate.update("delete from work_reports");
        jdbcTemplate.update("delete from report_push_targets");
        jdbcTemplate.update("delete from report_configs");
        jdbcTemplate.update("delete from report_templates");
        jdbcTemplate.update("delete from fixed_work_completions");
        jdbcTemplate.update("delete from fixed_work_items");
        jdbcTemplate.update("delete from future_plans");
        jdbcTemplate.update("delete from work_plans");
        jdbcTemplate.update("delete from work_logs");
        jdbcTemplate.update("delete from user_devices");
        jdbcTemplate.update("delete from user_module_entitlements");
        jdbcTemplate.update("delete from users");
    }

    @Test
    void unauthorizedUserGetsForbiddenWhenModuleNotEntitled() throws Exception {
        Long userId = insertUser("unauthorized@example.com", "active");
        insertDevice(userId, "device-001", "active");
        // no work-report entitlement
        String token = userAccessToken("unauthorized@example.com");

        mockMvc.perform(get("/api/client/work-report/logs?deviceId=device-001&date=2026-06-21")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void authorizedUserCanCreateAndListWorkLog() throws Exception {
        String token = prepareAuthorizedUser();

        mockMvc.perform(post("/api/client/work-report/logs?deviceId=device-001")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"logDate\":\"2026-06-21\",\"content\":\"完成了登录接口\",\"tags\":\"后端,登录\",\"source\":\"MANUAL\",\"sortOrder\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").value("完成了登录接口"));

        mockMvc.perform(get("/api/client/work-report/logs?deviceId=device-001&startDate=2026-06-21&endDate=2026-06-21")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].content").value("完成了登录接口"));
    }

    @Test
    void authorizedUserCanUpdateAndDeleteWorkLog() throws Exception {
        String token = prepareAuthorizedUser();

        MvcResult createResult = mockMvc.perform(post("/api/client/work-report/logs?deviceId=device-001")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"logDate\":\"2026-06-21\",\"content\":\"原始内容\"}"))
                .andExpect(status().isOk())
                .andReturn();
        Long logId = extractId(createResult);

        mockMvc.perform(put("/api/client/work-report/logs/" + logId + "?deviceId=device-001")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"content\":\"更新后的内容\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").value("更新后的内容"));

        mockMvc.perform(delete("/api/client/work-report/logs/" + logId + "?deviceId=device-001")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from work_logs where id = ? and deleted = 0", Integer.class, logId);
        assertEquals(0, count);
    }

    @Test
    void userCannotAccessOtherUsersWorkLog() throws Exception {
        String token = prepareAuthorizedUser();
        Long otherUserId = insertUser("other@example.com", "active");
        jdbcTemplate.update(
                "insert into work_logs (user_id, log_date, content, source, sort_order, created_by, created_at, updated_by, updated_at, deleted) " +
                        "values (?, '2026-06-21', '他人记录', 'MANUAL', 0, 0, CURRENT_TIMESTAMP, 0, CURRENT_TIMESTAMP, 0)",
                otherUserId);
        Long otherLogId = jdbcTemplate.queryForObject("select id from work_logs where user_id = ? limit 1", Long.class, otherUserId);

        mockMvc.perform(put("/api/client/work-report/logs/" + otherLogId + "?deviceId=device-001")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"content\":\"篡改\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void authorizedUserCanCreateFixedWorkAndToggleComplete() throws Exception {
        String token = prepareAuthorizedUser();

        MvcResult createResult = mockMvc.perform(post("/api/client/work-report/fixed-work?deviceId=device-001")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"content\":\"写接口文档\",\"recurrenceType\":\"DAILY\",\"reminderTime\":\"09:00\",\"reminderEnabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.completedToday").value(false))
                .andReturn();
        Long itemId = extractId(createResult);

        mockMvc.perform(post("/api/client/work-report/fixed-work/" + itemId + "/toggle-complete?deviceId=device-001")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.completedToday").value(true));
    }

    @Test
    void authorizedUserCanSaveAndListReportConfig() throws Exception {
        String token = prepareAuthorizedUser();
        Long templateId = insertDefaultTemplate();

        mockMvc.perform(post("/api/client/work-report/configs?deviceId=device-001")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"name\":\"我的日报\",\"reportType\":\"DAILY\",\"templateId\":" + templateId + ",\"cronExpression\":\"0 9 * * *\",\"timezone\":\"Asia/Shanghai\",\"enabled\":true,\"aiEnabled\":true,\"pushTargets\":[{\"platform\":\"feishu\",\"targetType\":\"webhook\",\"targetId\":\"https://hook\",\"credential\":\"secret\"}]" + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("我的日报"))
                .andExpect(jsonPath("$.data.pushTargets[0].hasCredential").value(true));

        mockMvc.perform(get("/api/client/work-report/configs?deviceId=device-001")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("我的日报"));
    }

    @Test
    void rejectInvalidCronExpression() throws Exception {
        String token = prepareAuthorizedUser();
        Long templateId = insertDefaultTemplate();

        mockMvc.perform(post("/api/client/work-report/configs?deviceId=device-001")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"name\":\"我的日报\",\"reportType\":\"DAILY\",\"templateId\":" + templateId + ",\"cronExpression\":\"invalid\",\"enabled\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    private String prepareAuthorizedUser() throws Exception {
        Long userId = insertUser("wr-user@example.com", "active");
        insertDevice(userId, "device-001", "active");
        insertEntitlement(userId, "work-report", true, OffsetDateTime.now().plusDays(30));
        return userAccessToken("wr-user@example.com");
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
                        "values (?, ?, 'user', ?, true, false, 1, 0, 0, CURRENT_TIMESTAMP, 0, CURRENT_TIMESTAMP, 0)",
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

    private void insertEntitlement(Long userId, String moduleCode, boolean enabled, OffsetDateTime expiresAt) {
        jdbcTemplate.update(
                "insert into user_module_entitlements (user_id, module_code, enabled, expires_at, created_by, created_at, updated_by, updated_at, deleted) " +
                        "values (?, ?, ?, ?, 0, CURRENT_TIMESTAMP, 0, CURRENT_TIMESTAMP, 0)",
                userId, moduleCode, enabled, expiresAt
        );
    }

    private Long insertDefaultTemplate() {
        jdbcTemplate.update(
                "insert into report_templates (user_id, name, type, content, is_default, created_by, created_at, updated_by, updated_at, deleted) " +
                        "values (null, '默认日报', 'DAILY', '## 今日工作\\n{{logs}}', true, 0, CURRENT_TIMESTAMP, 0, CURRENT_TIMESTAMP, 0)"
        );
        return jdbcTemplate.queryForObject("select id from report_templates where name = '默认日报' order by id desc limit 1", Long.class);
    }

    private Long extractId(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\"id\":(\\d+)").matcher(body);
        if (matcher.find()) {
            return Long.valueOf(matcher.group(1));
        }
        throw new IllegalStateException("无法从响应中提取 id: " + body);
    }
}
