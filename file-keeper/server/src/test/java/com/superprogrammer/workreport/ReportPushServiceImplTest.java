package com.superprogrammer.workreport;

import com.superprogrammer.support.TestStoreConfig;
import com.superprogrammer.workreport.dto.SaveReportConfigRequest;
import com.superprogrammer.workreport.dto.WorkReportDto;
import com.superprogrammer.workreport.entity.PushDelivery;
import com.superprogrammer.workreport.entity.ReportPushTarget;
import com.superprogrammer.workreport.entity.WorkReport;
import com.superprogrammer.workreport.repository.PushDeliveryRepository;
import com.superprogrammer.workreport.repository.ReportPushTargetRepository;
import com.superprogrammer.workreport.repository.WorkReportRepository;
import com.superprogrammer.workreport.service.ReportConfigService;
import com.superprogrammer.workreport.service.ReportPushService;
import com.superprogrammer.workreport.service.WorkLogService;
import com.superprogrammer.workreport.service.WorkPlanService;
import com.superprogrammer.workreport.service.WorkReportService;
import com.superprogrammer.workreport.service.push.FeishuPusher;
import com.superprogrammer.workreport.service.push.Platform;
import com.superprogrammer.workreport.service.push.PushResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestStoreConfig.class)
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:report_push_service;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ReportPushServiceImplTest {

    @Autowired
    private ReportPushService reportPushService;

    @Autowired
    private WorkReportService workReportService;

    @Autowired
    private WorkLogService workLogService;

    @Autowired
    private WorkPlanService workPlanService;

    @Autowired
    private ReportConfigService reportConfigService;

    @Autowired
    private PushDeliveryRepository pushDeliveryRepository;

    @Autowired
    private ReportPushTargetRepository reportPushTargetRepository;

    @Autowired
    private WorkReportRepository workReportRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private FeishuPusher feishuPusher;

    @BeforeEach
    void cleanUp() {
        jdbcTemplate.update("delete from push_deliveries");
        jdbcTemplate.update("delete from report_push_targets");
        jdbcTemplate.update("delete from work_reports");
        jdbcTemplate.update("delete from work_logs");
        jdbcTemplate.update("delete from work_plans");
        jdbcTemplate.update("delete from report_configs");
        jdbcTemplate.update("delete from report_templates");
        jdbcTemplate.update("delete from users");
    }

    @Test
    void pushReportRecordsSuccessAndUpdatesStatus() {
        when(feishuPusher.supports(Platform.FEISHU)).thenReturn(true);
        when(feishuPusher.push(any(), any())).thenReturn(new PushResult(true, "ok", "response"));

        Long userId = insertUser("push-user@example.com");
        Long templateId = insertTemplate();
        Long configId = insertConfig(userId, templateId);
        Long targetId = insertTarget(configId);

        workLogService.create(userId, new com.superprogrammer.workreport.dto.CreateWorkLogRequest(
                LocalDate.now(), "log content", null, "MANUAL", 0));

        WorkReportDto reportDto = workReportService.generate(userId, configId);

        reportPushService.pushReport(reportDto.id());

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            WorkReport report = workReportRepository.findById(reportDto.id()).orElseThrow();
            assertEquals("PUSHED", report.getStatus());

            List<PushDelivery> deliveries = pushDeliveryRepository.findByReportId(reportDto.id());
            assertEquals(1, deliveries.size());
            assertEquals("SUCCESS", deliveries.get(0).getStatus());
            assertEquals(targetId, deliveries.get(0).getTargetId());
        });
    }

    @Test
    void pushReportRecordsFailureAndUpdatesStatus() {
        when(feishuPusher.supports(Platform.FEISHU)).thenReturn(true);
        when(feishuPusher.push(any(), any())).thenReturn(new PushResult(false, "failed", "error"));

        Long userId = insertUser("push-fail@example.com");
        Long templateId = insertTemplate();
        Long configId = insertConfig(userId, templateId);
        Long targetId = insertTarget(configId);

        workLogService.create(userId, new com.superprogrammer.workreport.dto.CreateWorkLogRequest(
                LocalDate.now(), "log content", null, "MANUAL", 0));

        WorkReportDto reportDto = workReportService.generate(userId, configId);

        reportPushService.pushReport(reportDto.id());

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            WorkReport report = workReportRepository.findById(reportDto.id()).orElseThrow();
            assertEquals("FAILED", report.getStatus());

            List<PushDelivery> deliveries = pushDeliveryRepository.findByReportId(reportDto.id());
            assertEquals(1, deliveries.size());
            assertEquals("FAILED", deliveries.get(0).getStatus());
            assertEquals(targetId, deliveries.get(0).getTargetId());
        });
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
                        "values (null, '测试模板', 'DAILY', '{{logs}}', true, 0, CURRENT_TIMESTAMP, 0, CURRENT_TIMESTAMP, 0)"
        );
        return jdbcTemplate.queryForObject("select id from report_templates where name = '测试模板' order by id desc limit 1", Long.class);
    }

    private Long insertConfig(Long userId, Long templateId) {
        jdbcTemplate.update(
                "insert into report_configs (user_id, name, report_type, template_id, cron_expression, timezone, enabled, ai_enabled, created_by, created_at, updated_by, updated_at, deleted) " +
                        "values (?, '日报配置', 'DAILY', ?, '0 0 9 * * ?', 'Asia/Shanghai', true, false, ?, CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP, 0)",
                userId, templateId, userId, userId
        );
        return jdbcTemplate.queryForObject("select id from report_configs where user_id = ? order by id desc limit 1", Long.class, userId);
    }

    private Long insertTarget(Long configId) {
        ReportPushTarget target = new ReportPushTarget();
        target.setConfigId(configId);
        target.setPlatform("FEISHU");
        target.setTargetType("GROUP");
        target.setTargetId("chat123");
        target.setCredential("{\"appId\":\"app\",\"appSecret\":\"secret\"}");
        target.setCreatedBy(0L);
        target.setUpdatedBy(0L);
        ReportPushTarget saved = reportPushTargetRepository.insert(target);
        return saved.getId();
    }
}
