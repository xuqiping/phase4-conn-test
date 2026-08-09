package com.superprogrammer.common.audit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * AuditLogService 单测（LOG-FR-10 / 安全检查：异步落库失败不阻断主流程）。
 * 注入同步 Executor（Runnable::run）替代线程池，语义等价且可断言。
 */
class AuditLogServiceTest {

    private final AuditLogMapper mapper = mock(AuditLogMapper.class);
    private final AuditLogService service = new AuditLogService(mapper, Runnable::run);

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void recordBuildsRowFromMdc() {
        MDC.put("traceId", "trace-9");
        MDC.put("userId", "42");
        MDC.put("username", "alice");
        MDC.put("clientIp", "10.0.0.1");

        AuditLogEntity row = service.fromMdc("role", "update_permissions", "role", "3", "{\"a\":1}",
                AuditLogEntity.RESULT_SUCCESS);
        service.record(row);

        verify(mapper).insert(any(AuditLogEntity.class));
        assertThat(row.getTraceId()).isEqualTo("trace-9");
        assertThat(row.getUserId()).isEqualTo(42L);
        assertThat(row.getUsername()).isEqualTo("alice");
        assertThat(row.getClientIp()).isEqualTo("10.0.0.1");
    }

    @Test
    void anonymousUserIdDashBecomesNull() {
        MDC.put("userId", "-");
        AuditLogEntity row = service.fromMdc("auth", "login", null, null, "{}", AuditLogEntity.RESULT_SUCCESS);
        assertThat(row.getUserId()).isNull();
    }

    @Test
    void insertFailureSwallowedNeverThrows() {
        // 安全检查：模拟 DB 断 → 业务主流程不受影响（仅 WARN 计数）
        doThrow(new RuntimeException("db down")).when(mapper).insert(any(AuditLogEntity.class));
        assertThatCode(() -> service.record(service.fromMdc("role", "x", null, null, "{}", "SUCCESS")))
                .doesNotThrowAnyException();
    }
}
