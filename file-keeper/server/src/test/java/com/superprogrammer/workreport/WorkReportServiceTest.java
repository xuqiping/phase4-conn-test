package com.superprogrammer.workreport;

import com.superprogrammer.common.BusinessException;
import com.superprogrammer.support.TestStoreConfig;
import com.superprogrammer.workreport.dto.WorkReportDto;
import com.superprogrammer.workreport.service.FixedWorkService;
import com.superprogrammer.workreport.service.WorkLogService;
import com.superprogrammer.workreport.service.WorkReportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestStoreConfig.class)
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:work_report_service;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WorkReportServiceTest {

    @Autowired
    private WorkReportService workReportService;

    @Autowired
    private WorkLogService workLogService;

    @Autowired
    private FixedWorkService fixedWorkService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanUp() {
        jdbcTemplate.update("delete from work_reports");
        jdbcTemplate.update("delete from work_logs");
        jdbcTemplate.update("delete from fixed_work_completions");
        jdbcTemplate.update("delete from fixed_work_items");
        jdbcTemplate.update("delete from future_plans");
        jdbcTemplate.update("delete from work_plans");
        jdbcTemplate.update("delete from report_configs");
        jdbcTemplate.update("delete from report_templates");
        jdbcTemplate.update("delete from users");
    }

    @Test
    void generateDailyReportWithAiDisabled() {
        Long userId = insertUser("report-daily@example.com");
        Long templateId = insertTemplate("技术开发日报", "DAILY", "## 今日工作\n{{logs}}\n\n## 固定工作完成\n{{fixed_work}}");
        Long configId = insertConfig(userId, templateId, "DAILY", false);

        workLogService.create(userId, new com.superprogrammer.workreport.dto.CreateWorkLogRequest(
                LocalDate.now(), "Finished AI summary service", "backend", "MANUAL", 1));

        fixedWorkService.create(userId, new com.superprogrammer.workreport.dto.CreateFixedWorkItemRequest(
                "Write tests", null, "DAILY", java.time.LocalTime.of(9, 0), null, "Asia/Shanghai", false, null, 1));
        var fixedWorkItems = fixedWorkService.listByUserAndType(userId, "DAILY");
        Long itemId = fixedWorkItems.get(0).id();
        fixedWorkService.toggleComplete(userId, itemId);

        WorkReportDto report = workReportService.generate(userId, configId);

        assertNotNull(report.id());
        assertEquals("DAILY", report.reportType());
        assertEquals("GENERATED", report.status());
        assertTrue(report.title().contains("日报"));
        assertTrue(report.content().contains("Finished AI summary service"));
        assertTrue(report.content().contains("Write tests"));
        assertNotNull(report.completionRate());
        assertTrue(report.consecutiveMissDays() != null && report.consecutiveMissDays() >= 0);
    }

    @Test
    void generateWeeklyReport() {
        Long userId = insertUser("report-weekly@example.com");
        Long templateId = insertTemplate("管理周报", "WEEKLY", "# 本周总结\n{{ai_summary}}\n\n## 固定工作完成\n{{fixed_work}}");
        Long configId = insertConfig(userId, templateId, "WEEKLY", false);

        workLogService.create(userId, new com.superprogrammer.workreport.dto.CreateWorkLogRequest(
                LocalDate.now(), "周报记录", "后端", "MANUAL", 1));

        WorkReportDto report = workReportService.generate(userId, configId);

        assertEquals("WEEKLY", report.reportType());
        assertTrue(report.title().contains("周报"));
    }

    @Test
    void cannotGenerateReportForOtherUserConfig() {
        Long userA = insertUser("report-a@example.com");
        Long userB = insertUser("report-b@example.com");
        Long templateId = insertTemplate("技术开发日报", "DAILY", "{{logs}}");
        Long configId = insertConfig(userA, templateId, "DAILY", false);

        assertThrows(BusinessException.class, () -> workReportService.generate(userB, configId));
    }

    @Test
    void generateReportWithMissingConfigThrowsNotFound() {
        Long userId = insertUser("report-missing@example.com");

        assertThrows(BusinessException.class, () -> workReportService.generate(userId, 999999L));
    }

    private Long insertUser(String email) {
        jdbcTemplate.update(
                "insert into users (email, password_hash, role, status, email_verified, phone_verified, device_limit, offline_cache_minutes, created_by, created_at, updated_by, updated_at, deleted) " +
                        "values (?, 'hash', 'user', 'active', true, false, 1, 0, 0, CURRENT_TIMESTAMP, 0, CURRENT_TIMESTAMP, 0)",
                email
        );
        return jdbcTemplate.queryForObject("select id from users where email = ? order by id desc limit 1", Long.class, email);
    }

    private Long insertTemplate(String name, String type, String content) {
        jdbcTemplate.update(
                "insert into report_templates (user_id, name, type, content, is_default, created_by, created_at, updated_by, updated_at, deleted) " +
                        "values (null, ?, ?, ?, true, 0, CURRENT_TIMESTAMP, 0, CURRENT_TIMESTAMP, 0)",
                name, type, content
        );
        return jdbcTemplate.queryForObject("select id from report_templates where name = ? order by id desc limit 1", Long.class, name);
    }

    private Long insertConfig(Long userId, Long templateId, String reportType, boolean aiEnabled) {
        jdbcTemplate.update(
                "insert into report_configs (user_id, name, report_type, template_id, cron_expression, timezone, enabled, ai_enabled, include_inspiration_digest, created_by, created_at, updated_by, updated_at, deleted) " +
                        "values (?, ?, ?, ?, '0 9 * * *', 'Asia/Shanghai', true, ?, true, ?, CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP, 0)",
                userId, reportType + "配置", reportType, templateId, aiEnabled, userId, userId
        );
        return jdbcTemplate.queryForObject("select id from report_configs where user_id = ? order by id desc limit 1", Long.class, userId);
    }
}
