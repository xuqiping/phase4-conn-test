package com.superprogrammer.billing.service;

import com.superprogrammer.billing.entity.LlmUsageLogEntity;
import com.superprogrammer.billing.mapper.LlmUsageLogMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * UsageCollector 单测：fire-and-forget 语义。
 * 覆盖：异步落库 / DB 异常不外抛 / disabled 短路 / status 默认值。
 */
@ExtendWith(MockitoExtension.class)
class UsageCollectorTest {

    @Mock
    private LlmUsageLogMapper usageLogMapper;

    @InjectMocks
    private UsageCollector collector;

    @BeforeEach
    void init() {
        ReflectionTestUtils.setField(collector, "enabled", true);
        collector.init(); // 手动触发 @PostConstruct（纯单测无 Spring）
    }

    @AfterEach
    void teardown() {
        collector.shutdown(); // 关池，避免线程泄漏跨用例
        org.slf4j.MDC.clear(); // 清本类 traceId 用例种的 MDC，防跨用例泄漏
    }

    @Test
    void record_success_insertsAsync() {
        collector.record(1L, 7L, "GLOBAL", "gpt-4", LlmUsageLogEntity.KIND_CHAT,
                100, 50, new BigDecimal("0.003"), new BigDecimal("0.3"),
                LlmUsageLogEntity.STATUS_SUCCESS, null);
        // 异步：2s 内应落库一次
        verify(usageLogMapper, timeout(2000)).insert(any(LlmUsageLogEntity.class));
    }

    @Test
    void record_dbThrows_doesNotPropagate() {
        doThrow(new RuntimeException("DB down")).when(usageLogMapper).insert(any());
        // fire-and-forget：DB 异常被吞，调用线程不感知
        assertThatCode(() -> collector.record(1L, 7L, "GLOBAL", "gpt-4",
                LlmUsageLogEntity.KIND_CHAT, 1, 1, BigDecimal.ZERO, BigDecimal.ZERO,
                LlmUsageLogEntity.STATUS_SUCCESS, null)).doesNotThrowAnyException();
        // 等异步任务消化完，确认确实尝试了 insert（只是失败被吞）
        verify(usageLogMapper, timeout(2000)).insert(any());
    }

    @Test
    void record_disabled_skips() {
        ReflectionTestUtils.setField(collector, "enabled", false);
        collector.record(1L, 7L, "GLOBAL", "gpt-4", LlmUsageLogEntity.KIND_CHAT,
                1, 1, BigDecimal.ZERO, BigDecimal.ZERO, LlmUsageLogEntity.STATUS_SUCCESS, null);
        verify(usageLogMapper, never()).insert(any());
    }

    @Test
    void record_nullStatus_defaultsSuccess() {
        collector.record(1L, 7L, "GLOBAL", "gpt-4", LlmUsageLogEntity.KIND_CHAT,
                1, 1, BigDecimal.ZERO, BigDecimal.ZERO, null, null);
        org.mockito.ArgumentCaptor<LlmUsageLogEntity> cap =
                org.mockito.ArgumentCaptor.forClass(LlmUsageLogEntity.class);
        verify(usageLogMapper, timeout(2000)).insert(cap.capture());
        if (!LlmUsageLogEntity.STATUS_SUCCESS.equals(cap.getValue().getStatus())) {
            throw new AssertionError("status 应默认 SUCCESS，实=" + cap.getValue().getStatus());
        }
    }

    // ---------- 8x Chunk7：traceId/taskId 关联键 ----------

    @Test
    void record_traceIdInMdc_stampsRow() {
        // chat 路径：调用线程 MDC 有 traceId → usage 行带 traceId（与 audit_logs.trace_id 同源同值）
        org.slf4j.MDC.put("traceId", "trace-chat-123");
        collector.record(1L, 7L, "GLOBAL", "gpt-4", LlmUsageLogEntity.KIND_CHAT,
                100, 50, new BigDecimal("0.003"), new BigDecimal("0.3"),
                LlmUsageLogEntity.STATUS_SUCCESS, null);
        org.mockito.ArgumentCaptor<LlmUsageLogEntity> cap =
                org.mockito.ArgumentCaptor.forClass(LlmUsageLogEntity.class);
        verify(usageLogMapper, timeout(2000)).insert(cap.capture());
        if (!"trace-chat-123".equals(cap.getValue().getTraceId())) {
            throw new AssertionError("traceId 应=trace-chat-123，实=" + cap.getValue().getTraceId());
        }
        if (cap.getValue().getTaskId() != null) {
            throw new AssertionError("chat 调用 taskId 应=null，实=" + cap.getValue().getTaskId());
        }
    }

    @Test
    void record_noMdc_traceIdNull_taskIdStamped() {
        // media 路径：worker 线程无 MDC traceId → null 不崩；taskId 从参数显式落（任务 id）
        org.slf4j.MDC.clear();
        collector.record(100L, 7L, "GLOBAL", "seedance", LlmUsageLogEntity.KIND_VIDEO,
                200000, null, new BigDecimal("0.5"), new BigDecimal("50"),
                LlmUsageLogEntity.STATUS_SUCCESS, null, 9L);
        org.mockito.ArgumentCaptor<LlmUsageLogEntity> cap =
                org.mockito.ArgumentCaptor.forClass(LlmUsageLogEntity.class);
        verify(usageLogMapper, timeout(2000)).insert(cap.capture());
        if (cap.getValue().getTraceId() != null) {
            throw new AssertionError("无 MDC 时 traceId 应=null，实=" + cap.getValue().getTraceId());
        }
        if (!Long.valueOf(9L).equals(cap.getValue().getTaskId())) {
            throw new AssertionError("taskId 应=9，实=" + cap.getValue().getTaskId());
        }
    }
}
