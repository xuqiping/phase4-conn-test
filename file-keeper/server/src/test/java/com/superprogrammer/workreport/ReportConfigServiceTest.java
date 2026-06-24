package com.superprogrammer.workreport;

import com.superprogrammer.common.BusinessException;
import com.superprogrammer.common.ErrorCode;
import com.superprogrammer.support.TestStoreConfig;
import com.superprogrammer.workreport.dto.ReportConfigDto;
import com.superprogrammer.workreport.dto.ReportPushTargetRequest;
import com.superprogrammer.workreport.dto.SaveReportConfigRequest;
import com.superprogrammer.workreport.service.ReportConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestStoreConfig.class)
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:report_config_service;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ReportConfigServiceTest {

    @Autowired
    private ReportConfigService reportConfigService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanUp() {
        jdbcTemplate.update("delete from report_push_targets");
        jdbcTemplate.update("delete from report_configs");
        jdbcTemplate.update("delete from report_templates");
        jdbcTemplate.update("delete from users");
    }

    @Test
    void saveConfigWithPushTargets() {
        Long userId = insertUser("config-user@example.com");
        Long templateId = insertTemplate();

        SaveReportConfigRequest request = new SaveReportConfigRequest(
                null,
                "日报",
                "DAILY",
                templateId,
                "0 9 * * *",
                "Asia/Shanghai",
                true,
                true,
                List.of(new ReportPushTargetRequest(null, "feishu", "webhook", "https://hook", "secret"))
        );

        ReportConfigDto dto = reportConfigService.save(userId, request);

        assertEquals("日报", dto.name());
        assertEquals(1, dto.pushTargets().size());
        assertTrue(dto.pushTargets().get(0).hasCredential());
    }

    @Test
    void updateConfigRemovesOrphanPushTargets() {
        Long userId = insertUser("config-update@example.com");
        Long templateId = insertTemplate();

        SaveReportConfigRequest createRequest = new SaveReportConfigRequest(
                null, "日报", "DAILY", templateId, "0 9 * * *", "Asia/Shanghai", true, true,
                List.of(
                        new ReportPushTargetRequest(null, "feishu", "webhook", "https://hook1", "secret1"),
                        new ReportPushTargetRequest(null, "feishu", "webhook", "https://hook2", "secret2")
                )
        );
        ReportConfigDto created = reportConfigService.save(userId, createRequest);
        Long firstTargetId = created.pushTargets().get(0).id();

        SaveReportConfigRequest updateRequest = new SaveReportConfigRequest(
                created.id(), "日报", "DAILY", templateId, "0 10 * * *", "Asia/Shanghai", true, true,
                List.of(new ReportPushTargetRequest(firstTargetId, "feishu", "webhook", "https://hook1", "new-secret"))
        );
        ReportConfigDto updated = reportConfigService.save(userId, updateRequest);

        assertEquals(1, updated.pushTargets().size());
        Integer activeCount = jdbcTemplate.queryForObject(
                "select count(*) from report_push_targets where config_id = ? and deleted = 0", Integer.class, created.id());
        assertEquals(1, activeCount);
    }

    @Test
    void deleteConfigSoftDeletesTargets() {
        Long userId = insertUser("config-delete@example.com");
        Long templateId = insertTemplate();

        SaveReportConfigRequest request = new SaveReportConfigRequest(
                null, "日报", "DAILY", templateId, "0 9 * * *", "Asia/Shanghai", true, true,
                List.of(new ReportPushTargetRequest(null, "feishu", "webhook", "https://hook", "secret"))
        );
        ReportConfigDto created = reportConfigService.save(userId, request);

        reportConfigService.delete(userId, created.id());

        Integer configCount = jdbcTemplate.queryForObject(
                "select count(*) from report_configs where id = ? and deleted = 0", Integer.class, created.id());
        Integer targetCount = jdbcTemplate.queryForObject(
                "select count(*) from report_push_targets where config_id = ? and deleted = 0", Integer.class, created.id());
        assertEquals(0, configCount);
        assertEquals(0, targetCount);
    }

    @Test
    void rejectInvalidCronExpression() {
        Long userId = insertUser("config-cron@example.com");
        Long templateId = insertTemplate();

        SaveReportConfigRequest request = new SaveReportConfigRequest(
                null, "日报", "DAILY", templateId, "invalid-cron", "Asia/Shanghai", true, true, null
        );

        BusinessException ex = assertThrows(BusinessException.class, () -> reportConfigService.save(userId, request));
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), ex.getCode());
    }

    @Test
    void cannotAccessOtherUsersConfig() {
        Long userA = insertUser("config-a@example.com");
        Long userB = insertUser("config-b@example.com");
        Long templateId = insertTemplate();

        SaveReportConfigRequest request = new SaveReportConfigRequest(
                null, "日报", "DAILY", templateId, "0 9 * * *", "Asia/Shanghai", true, true, null
        );
        ReportConfigDto created = reportConfigService.save(userA, request);

        assertThrows(BusinessException.class, () -> reportConfigService.getById(userB, created.id()));
        assertThrows(BusinessException.class, () -> reportConfigService.delete(userB, created.id()));
    }

    private Long insertUser(String email) {
        jdbcTemplate.update(
                "insert into users (email, password_hash, role, status, email_verified, phone_verified, device_limit, offline_cache_minutes, created_by, created_at, updated_by, updated_at, deleted) " +
                        "values (?, 'hash', 'user', 'active', true, false, 1, 0, 0, CURRENT_TIMESTAMP, 0, CURRENT_TIMESTAMP, 0)",
                email
        );
        return jdbcTemplate.queryForObject("select id from users where email = ? order by id desc limit 1", Long.class, email);
    }

    private Long insertTemplate() {
        jdbcTemplate.update(
                "insert into report_templates (user_id, name, type, content, is_default, created_by, created_at, updated_by, updated_at, deleted) " +
                        "values (null, '测试模板', 'DAILY', '内容', true, 0, CURRENT_TIMESTAMP, 0, CURRENT_TIMESTAMP, 0)"
        );
        return jdbcTemplate.queryForObject("select id from report_templates where name = '测试模板' order by id desc limit 1", Long.class);
    }
}
