package com.superprogrammer.billing.service;

import com.superprogrammer.billing.entity.LlmUsageLogEntity;
import com.superprogrammer.common.audit.AuditLogEntity;
import com.superprogrammer.common.audit.AuditLogService;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LlmBillingService 单测：计费链 + 铁律「绝不抛回 LLM 出口」。
 */
@ExtendWith(MockitoExtension.class)
class LlmBillingServiceTest {

    @Mock private PricingService pricingService;
    @Mock private PointsRatioService ratioService;
    @Mock private PointsWalletService walletService;
    @Mock private UsageCollector usageCollector;
    @Mock private AuditLogService auditLogService;

    @InjectMocks private LlmBillingService billing;

    /** 打开 chat 审计开关（@Value 在单测不注入，boolean 默认 false）。 */
    private void enableChatAudit() {
        ReflectionTestUtils.setField(billing, "chatAuditEnabled", true);
    }

    @Test
    void onSuccess_happyPath_chargesAndRecords() {
        when(walletService.isEnabled()).thenReturn(true);
        when(pricingService.computeCost(eq("CHAT"), anyLong(), eq("gpt-4"),
                eq(100), eq(50), eq(0), eq(0))).thenReturn(new BigDecimal("0.003"));
        when(ratioService.toPoints(new BigDecimal("0.003"))).thenReturn(new BigDecimal("0.3"));
        when(walletService.charge(eq(1L), eq(new BigDecimal("0.3")), eq("CHAT"),
                eq(null), eq("gpt-4"))).thenReturn(new BigDecimal("99.7"));

        BigDecimal after = billing.onSuccess(1L, 7L, "GLOBAL", "gpt-4", "CHAT", 100, 50);

        assertThat(after).isEqualByComparingTo("99.7");
        verify(usageCollector).record(eq(1L), eq(7L), eq("GLOBAL"), eq("gpt-4"), eq("CHAT"),
                eq(100), eq(50), eq(new BigDecimal("0.003")), eq(new BigDecimal("0.3")),
                eq(LlmUsageLogEntity.STATUS_SUCCESS), eq(null));
    }

    @Test
    void onSuccess_pricingNotFound_recordsFailed_noThrow_noCharge() {
        when(walletService.isEnabled()).thenReturn(true);
        when(pricingService.computeCost(any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenThrow(new BusinessException(ErrorCode.PRICING_NOT_FOUND));

        // 铁律：计费失败不得抛回出口
        assertThatCode(() -> billing.onSuccess(1L, 7L, "GLOBAL", "gpt-4", "CHAT", 100, 50))
                .doesNotThrowAnyException();

        verify(walletService, never()).charge(any(), any(), any(), any(), any());
        verify(usageCollector).record(eq(1L), any(), any(), any(), any(),
                any(), any(), eq(null), eq(null), eq(LlmUsageLogEntity.STATUS_FAILED), any());
    }

    @Test
    void onSuccess_systemUser_chargeNoops_stillRecords() {
        when(walletService.isEnabled()).thenReturn(true);
        when(pricingService.computeCost(any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new BigDecimal("0.001"));
        when(ratioService.toPoints(any())).thenReturn(new BigDecimal("0.1"));
        when(walletService.charge(eq(null), any(), any(), any(), any())).thenReturn(null); // 系统调用短路

        BigDecimal after = billing.onSuccess(null, 7L, "GLOBAL", "embed-v1", "EMBED", 10, 0);

        assertThat(after).isNull();
        // 仍采 SUCCESS（采不扣）
        verify(usageCollector).record(eq(null), any(), any(), any(), eq("EMBED"),
                any(), any(), any(), any(), eq(LlmUsageLogEntity.STATUS_SUCCESS), any());
    }

    @Test
    void onSuccess_unexpectedException_swallowed() {
        when(walletService.isEnabled()).thenReturn(true);
        when(pricingService.computeCost(any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenThrow(new RuntimeException("DB connection lost"));

        // 任何意外都吞，不回归出口
        assertThatCode(() -> billing.onSuccess(1L, 7L, "GLOBAL", "gpt-4", "CHAT", 1, 1))
                .doesNotThrowAnyException();
        verify(walletService, never()).charge(any(), any(), any(), any(), any());
    }

    @Test
    void onFailure_recordsFailedOnly() {
        billing.onFailure(1L, 7L, "GLOBAL", "gpt-4", "CHAT", "timeout");
        verify(usageCollector).record(eq(1L), eq(7L), eq("GLOBAL"), eq("gpt-4"), eq("CHAT"),
                eq(null), eq(null), eq(null), eq(null), eq(LlmUsageLogEntity.STATUS_FAILED), eq("timeout"));
        verify(walletService, never()).charge(any(), any(), any(), any(), any());
    }

    // ---------- 8x Chunk4：chat_completed 审计 ----------

    @Test
    void onSuccess_chatKind_auditsChatCompletedWithPointsSnapshot() {
        enableChatAudit();
        when(walletService.isEnabled()).thenReturn(true);
        when(pricingService.computeCost(eq("CHAT"), anyLong(), eq("gpt-4"),
                eq(100), eq(50), eq(0), eq(0))).thenReturn(new BigDecimal("0.003"));
        when(ratioService.toPoints(new BigDecimal("0.003"))).thenReturn(new BigDecimal("0.3"));
        when(walletService.charge(eq(1L), eq(new BigDecimal("0.3")), eq("CHAT"),
                eq(null), eq("gpt-4"))).thenReturn(new BigDecimal("99.7"));

        billing.onSuccess(1L, 7L, "GLOBAL", "gpt-4", "CHAT", 100, 50);

        // 行2：chat_completed SUCCESS，detail 含 model+tokens+pointsConsumed（单一计算源=本帧 0.3）
        verify(auditLogService).recordTask(eq("chat"), eq("chat_completed"), eq("chat_session"),
                isNull(), eq(1L), isNull(), isNull(),
                org.mockito.ArgumentMatchers.contains("\"pointsConsumed\":0.3"),
                eq(AuditLogEntity.RESULT_SUCCESS));
    }

    @Test
    void onSuccess_embedKind_skipsChatAudit() {
        enableChatAudit();
        when(walletService.isEnabled()).thenReturn(true);
        when(pricingService.computeCost(any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new BigDecimal("0.001"));
        when(ratioService.toPoints(any())).thenReturn(new BigDecimal("0.1"));
        when(walletService.charge(eq(null), any(), any(), any(), any())).thenReturn(null);

        billing.onSuccess(null, 7L, "GLOBAL", "embed-v1", "EMBED", 10, 0);

        // EMBED + userId=null：不记 chat_completed（门控 kind=CHAT && userId!=null）
        verify(auditLogService, never()).recordTask(any(), any(), any(), any(),
                any(), any(), any(), any(), any());
    }

    @Test
    void onSuccess_chatKind_auditDisabled_skipsAudit() {
        // chatAuditEnabled 默认 false（不调 enableChatAudit）→ 即使 CHAT 也不记
        when(walletService.isEnabled()).thenReturn(true);
        when(pricingService.computeCost(any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new BigDecimal("0.001"));
        when(ratioService.toPoints(any())).thenReturn(new BigDecimal("0.1"));
        when(walletService.charge(any(), any(), any(), any(), any())).thenReturn(new BigDecimal("100"));

        billing.onSuccess(1L, 7L, "GLOBAL", "gpt-4", "CHAT", 10, 5);

        verify(auditLogService, never()).recordTask(any(), any(), any(), any(),
                any(), any(), any(), any(), any());
    }

    @Test
    void onFailure_chatKind_auditsChatCompletedFail() {
        enableChatAudit();
        billing.onFailure(1L, 7L, "GLOBAL", "gpt-4", "CHAT", "模型超时");

        // 失败分支：chat_completed FAIL，detail 含 reason
        verify(auditLogService).recordTask(eq("chat"), eq("chat_completed"), eq("chat_session"),
                isNull(), eq(1L), isNull(), isNull(),
                org.mockito.ArgumentMatchers.contains("模型超时"),
                eq(AuditLogEntity.RESULT_FAIL));
    }
}
