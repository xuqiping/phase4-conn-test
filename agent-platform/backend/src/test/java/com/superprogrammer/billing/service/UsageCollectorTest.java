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
}
