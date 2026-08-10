package com.superprogrammer.billing.service;

import com.superprogrammer.common.audit.AuditLogEntity;
import com.superprogrammer.common.audit.AuditLogService;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.system.service.SystemSettingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 安全体系 S2 · L7 低余额并行闸门（SEC-FR-126）InflightGateService 单测。
 * 断言：阈值判定/在途上限/计数退回/审计留痕/Redis 故障降级放行/释放负值清零。
 */
@ExtendWith(MockitoExtension.class)
class InflightGateServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private PointsWalletService walletService;
    @Mock
    private SystemSettingService systemSettingService;
    @Mock
    private AuditLogService auditLogService;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private InflightGateService gate;

    @BeforeEach
    void setUp() {
        gate = new InflightGateService(redisTemplate, walletService, systemSettingService, auditLogService);
        lenient().when(walletService.isEnabled()).thenReturn(true);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(systemSettingService.getLong(SystemSettingService.BILLING_LOW_BALANCE_THRESHOLD, 100L))
                .thenReturn(100L);
        lenient().when(systemSettingService.getLong(SystemSettingService.BILLING_LOW_BALANCE_MAX_INFLIGHT, 1L))
                .thenReturn(1L);
    }

    @Test
    void acquire_systemCallOrBillingDisabled_notGated() {
        assertFalse(gate.acquire(null));
        verifyNoInteractions(redisTemplate);

        when(walletService.isEnabled()).thenReturn(false);
        assertFalse(gate.acquire(1L));
        verifyNoInteractions(redisTemplate);
    }

    @Test
    void acquire_zeroOrNegativeBalance_notGated_requireAffordableWillReject() {
        when(walletService.getBalance(1L)).thenReturn(BigDecimal.ZERO);

        assertFalse(gate.acquire(1L));
        verifyNoInteractions(redisTemplate); // 不占用槽位，语义交给 INSUFFICIENT_POINTS
    }

    @Test
    void acquire_balanceAboveThreshold_alwaysAdmitted() {
        when(walletService.getBalance(1L)).thenReturn(new BigDecimal("500"));
        when(valueOperations.increment("inflight:u:1")).thenReturn(3L);

        assertTrue(gate.acquire(1L)); // 余额充足：并行不受限
    }

    // AC-SEC-FR-126：低余额首个在途放行（INCR=1 且建 TTL 兜底）
    @Test
    void acquire_lowBalance_firstInflight_admittedWithTtl() {
        when(walletService.getBalance(1L)).thenReturn(new BigDecimal("99"));
        when(valueOperations.increment("inflight:u:1")).thenReturn(1L);

        assertTrue(gate.acquire(1L));
        verify(redisTemplate).expire("inflight:u:1", 30L, TimeUnit.MINUTES);
    }

    // AC-SEC-FR-126：低余额第二个并行 → 退回计数 + 审计 billing/inflight_rejected + 42902
    @Test
    void acquire_lowBalance_secondInflight_rejected() {
        when(walletService.getBalance(1L)).thenReturn(new BigDecimal("99"));
        when(valueOperations.increment("inflight:u:1")).thenReturn(2L);
        when(valueOperations.decrement("inflight:u:1")).thenReturn(1L);
        when(auditLogService.fromMdc(eq("billing"), eq("inflight_rejected"), eq("user"),
                eq("1"), anyString(), eq(AuditLogEntity.RESULT_FAIL)))
                .thenReturn(new AuditLogEntity());

        BusinessException e = assertThrows(BusinessException.class, () -> gate.acquire(1L));

        assertEquals(42902, e.getCode());
        verify(valueOperations).decrement("inflight:u:1"); // 计数退回，不占槽
        verify(auditLogService).record(any(AuditLogEntity.class));
    }

    @Test
    void acquire_lowBalance_maxInflight2_allowsSecond() {
        when(systemSettingService.getLong(SystemSettingService.BILLING_LOW_BALANCE_MAX_INFLIGHT, 1L))
                .thenReturn(2L);
        when(walletService.getBalance(1L)).thenReturn(new BigDecimal("99"));
        when(valueOperations.increment("inflight:u:1")).thenReturn(2L);

        assertTrue(gate.acquire(1L)); // 管理员放宽到 2 → 第二个仍放行
    }

    // 降级红线：Redis 故障 → 放行（可用性 > 强制力）
    @Test
    void acquire_redisDown_degradesOpen() {
        when(walletService.getBalance(1L)).thenReturn(new BigDecimal("99"));
        when(valueOperations.increment(anyString())).thenThrow(new RuntimeException("redis down"));

        assertFalse(assertDoesNotThrow(() -> gate.acquire(1L)));
    }

    @Test
    void release_decrementsToZero_deletesKey() {
        when(valueOperations.decrement("inflight:u:1")).thenReturn(0L);

        gate.release(1L);

        verify(redisTemplate).delete("inflight:u:1");
    }

    @Test
    void release_negativeMismatch_floorsAndDeletes() {
        when(valueOperations.decrement("inflight:u:1")).thenReturn(-1L);

        gate.release(1L);

        verify(redisTemplate).delete("inflight:u:1"); // 错配场景 fail-open 清零
    }

    @Test
    void release_redisDown_swallowed() {
        when(valueOperations.decrement(anyString())).thenThrow(new RuntimeException("redis down"));

        assertDoesNotThrow(() -> gate.release(1L));
    }

    // 计费运行期关闭不影响 release 配对（submit 已计数 → 关计费 → worker 仍须释放；release 不看开关）
    @Test
    void release_billingDisabledMidFlight_stillReleases() {
        when(valueOperations.decrement("inflight:u:1")).thenReturn(0L);

        gate.release(1L);

        verify(valueOperations).decrement("inflight:u:1");
    }

    // 阈值读取失败（DB 抖动）→ 降级放行且不动计数（避免 INCR 后无人 release 的泄漏窗口）
    @Test
    void acquire_settingsReadFails_degradesWithoutTouchingCounter() {
        when(systemSettingService.getLong(SystemSettingService.BILLING_LOW_BALANCE_THRESHOLD, 100L))
                .thenThrow(new RuntimeException("db down"));

        assertFalse(gate.acquire(1L));
        verifyNoInteractions(redisTemplate);
    }

    // 余额复用：调用方传入余额时不再重复查库
    @Test
    void acquire_withBalance_skipsBalanceQuery() {
        when(valueOperations.increment("inflight:u:1")).thenReturn(1L);

        assertTrue(gate.acquire(1L, new BigDecimal("99")));

        verify(walletService, never()).getBalance(anyLong());
    }
}
