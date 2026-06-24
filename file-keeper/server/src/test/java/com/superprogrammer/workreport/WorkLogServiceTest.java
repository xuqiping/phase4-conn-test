package com.superprogrammer.workreport;

import com.superprogrammer.common.BusinessException;
import com.superprogrammer.support.TestStoreConfig;
import com.superprogrammer.workreport.dto.CreateWorkLogRequest;
import com.superprogrammer.workreport.dto.UpdateWorkLogRequest;
import com.superprogrammer.workreport.dto.WorkLogDto;
import com.superprogrammer.workreport.service.WorkLogService;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestStoreConfig.class)
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:work_log_service;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WorkLogServiceTest {

    @Autowired
    private WorkLogService workLogService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanUp() {
        jdbcTemplate.update("delete from work_logs");
        jdbcTemplate.update("delete from users");
    }

    @Test
    void createAndListByDate() {
        Long userId = insertUser("log-user@example.com");
        LocalDate date = LocalDate.of(2026, 6, 21);

        workLogService.create(userId, new CreateWorkLogRequest(date, "完成接口", "后端", "MANUAL", 1));
        workLogService.create(userId, new CreateWorkLogRequest(date, "修复 Bug", "前端", "MANUAL", 2));

        List<WorkLogDto> logs = workLogService.listByUserAndDate(userId, date);
        assertEquals(2, logs.size());
        assertTrue(logs.stream().anyMatch(l -> "完成接口".equals(l.content())));
    }

    @Test
    void updateWorkLog() {
        Long userId = insertUser("log-update@example.com");
        LocalDate date = LocalDate.of(2026, 6, 21);

        WorkLogDto created = workLogService.create(userId, new CreateWorkLogRequest(date, "原始内容", null, "MANUAL", 0));
        WorkLogDto updated = workLogService.update(userId, created.id(), new UpdateWorkLogRequest("更新内容", "标签", "AUTO", 1));

        assertEquals("更新内容", updated.content());
        assertEquals("标签", updated.tags());
    }

    @Test
    void deleteWorkLog() {
        Long userId = insertUser("log-delete@example.com");
        LocalDate date = LocalDate.of(2026, 6, 21);

        WorkLogDto created = workLogService.create(userId, new CreateWorkLogRequest(date, "待删除", null, "MANUAL", 0));
        workLogService.delete(userId, created.id());

        List<WorkLogDto> logs = workLogService.listByUserAndDate(userId, date);
        assertTrue(logs.isEmpty());
    }

    @Test
    void cannotAccessOtherUsersLog() {
        Long userA = insertUser("log-a@example.com");
        Long userB = insertUser("log-b@example.com");
        LocalDate date = LocalDate.of(2026, 6, 21);

        WorkLogDto created = workLogService.create(userA, new CreateWorkLogRequest(date, "A的内容", null, "MANUAL", 0));

        assertThrows(BusinessException.class, () -> workLogService.update(userB, created.id(), new UpdateWorkLogRequest("篡改", null, null, 0)));
        assertThrows(BusinessException.class, () -> workLogService.delete(userB, created.id()));
    }

    private Long insertUser(String email) {
        jdbcTemplate.update(
                "insert into users (email, password_hash, role, status, email_verified, phone_verified, device_limit, offline_cache_minutes, created_by, created_at, updated_by, updated_at, deleted) " +
                        "values (?, 'hash', 'user', 'active', true, false, 1, 0, 0, CURRENT_TIMESTAMP, 0, CURRENT_TIMESTAMP, 0)",
                email
        );
        return jdbcTemplate.queryForObject("select id from users where email = ? order by id desc limit 1", Long.class, email);
    }
}
