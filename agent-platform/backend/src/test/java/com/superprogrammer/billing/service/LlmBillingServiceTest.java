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
import static org.mockito.ArgumentMatchers.anyBoolean;
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
    /** 计划5 Step4：组池计费分支 mock。 */
    @Mock private com.superprogrammer.projectgroup.service.ProjectGroupWalletService groupWalletService;
    @Mock private UsageCollector usageCollector;
    @Mock private AuditLogService auditLogService;

    @InjectMocks private LlmBillingService billing;

    /** 打开 chat 审计开关（@Value 在单测不注入，boolean 默认 false）。 */
    private void enableChatAudit() {
        ReflectionTestUtils.setField(billing, "chatAuditEnabled", true);
    }

    /** B3：打开聊天预扣开关 + 折算系数/est 帽（@Value 单测不注入；isEnabled 由各用例按需 stub）。 */
    private void enableChatHold() {
        ReflectionTestUtils.setField(billing, "chatHoldEnabled", true);
        ReflectionTestUtils.setField(billing, "charPerToken", 1.6);
        ReflectionTestUtils.setField(billing, "holdEstMaxTokens", 2048);
    }

    @Test
    void onSuccess_happyPath_chargesAndRecords() {
        when(walletService.isEnabled()).thenReturn(true);
        when(pricingService.computeCost(eq("CHAT"), anyLong(), eq("gpt-4"),
                eq(100), eq(50), eq(0), eq(0), anyBoolean(), isNull(), isNull())).thenReturn(new BigDecimal("0.003"));
        when(ratioService.toPoints(new BigDecimal("0.003"))).thenReturn(new BigDecimal("0.3"));
        when(walletService.charge(eq(1L), eq(new BigDecimal("0.3")), eq("CHAT"),
                eq(null), eq("gpt-4"))).thenReturn(new BigDecimal("99.7"));

        BigDecimal after = billing.onSuccess(1L, 7L, "GLOBAL", "gpt-4", "CHAT", 100, 50);

        assertThat(after).isEqualByComparingTo("99.7");
        verify(usageCollector).record(eq(1L), eq(7L), eq("GLOBAL"), eq("gpt-4"), eq("CHAT"),
                eq(100), eq(50), eq(new BigDecimal("0.003")), eq(new BigDecimal("0.3")),
                eq(LlmUsageLogEntity.STATUS_SUCCESS), eq(null), eq(null), eq(null), eq(null), eq(null));
    }

    @Test
    void onSuccess_pricingNotFound_recordsFailed_noThrow_noCharge() {
        when(walletService.isEnabled()).thenReturn(true);
        when(pricingService.computeCost(any(), any(), any(), any(), any(), anyInt(), anyInt(),
                anyBoolean(), any(), any()))
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
        when(pricingService.computeCost(any(), any(), any(), any(), any(), anyInt(), anyInt(),
                anyBoolean(), any(), any()))
                .thenReturn(new BigDecimal("0.001"));
        when(ratioService.toPoints(any())).thenReturn(new BigDecimal("0.1"));
        when(walletService.charge(eq(null), any(), any(), any(), any())).thenReturn(null); // 系统调用短路

        BigDecimal after = billing.onSuccess(null, 7L, "GLOBAL", "embed-v1", "EMBED", 10, 0);

        assertThat(after).isNull();
        // 仍采 SUCCESS（采不扣）
        verify(usageCollector).record(eq(null), any(), any(), any(), eq("EMBED"),
                any(), any(), any(), any(), eq(LlmUsageLogEntity.STATUS_SUCCESS), any(),
                any(), any(), any(), any());
    }

    @Test
    void onSuccess_unexpectedException_swallowed() {
        when(walletService.isEnabled()).thenReturn(true);
        when(pricingService.computeCost(any(), any(), any(), any(), any(), anyInt(), anyInt(),
                anyBoolean(), any(), any()))
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
                eq(100), eq(50), eq(0), eq(0), anyBoolean(), isNull(), isNull())).thenReturn(new BigDecimal("0.003"));
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
        when(pricingService.computeCost(any(), any(), any(), any(), any(), anyInt(), anyInt(),
                anyBoolean(), any(), any()))
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
        when(pricingService.computeCost(any(), any(), any(), any(), any(), anyInt(), anyInt(),
                anyBoolean(), any(), any()))
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

    // ---------- 计划5 Step4：组池计费分支 ----------

    /** chat 选组 → chargeGroup（组池+成员记账），个人 charge 不动，usage 落 gid（账单事实源）。 */
    @Test
    void onSuccess_withGroup_chargesGroupNotPersonal_usageCarriesGid() {
        when(walletService.isEnabled()).thenReturn(true);
        when(pricingService.computeCost(eq("CHAT"), anyLong(), eq("gpt-4"),
                eq(100), eq(50), eq(0), eq(0), anyBoolean(), isNull(), isNull())).thenReturn(new BigDecimal("0.003"));
        when(ratioService.toPoints(new BigDecimal("0.003"))).thenReturn(new BigDecimal("0.3"));
        when(groupWalletService.chargeGroup(eq(5L), eq(1L), eq(new BigDecimal("0.3")),
                eq("CHAT"), eq("gpt-4"), isNull(), eq(true))).thenReturn(new BigDecimal("49.7"));

        BigDecimal after = billing.onSuccess(1L, 7L, "GLOBAL", "gpt-4", "CHAT", 100, 50,
                LlmUsageLogEntity.STATUS_SUCCESS, null, 5L);

        assertThat(after).isEqualByComparingTo("49.7");
        verify(groupWalletService).chargeGroup(5L, 1L, new BigDecimal("0.3"), "CHAT", "gpt-4", null, true);
        verify(walletService, never()).charge(any(), any(), any(), any(), any());
        verify(usageCollector).record(eq(1L), eq(7L), eq("GLOBAL"), eq("gpt-4"), eq("CHAT"),
                eq(100), eq(50), eq(new BigDecimal("0.003")), eq(new BigDecimal("0.3")),
                eq(LlmUsageLogEntity.STATUS_SUCCESS), eq(null), eq(null), eq(null), eq(5L), eq(null));
    }

    /**
     * 组扣费抛（V161 后资金不足已不抛，此为系统级错误：组已删/行缺失）→ 铁律吞不回归出口，
     * 记 FAILED usage 让 admin 可见缺口。
     */
    @Test
    void onSuccess_withGroup_chargeGroupThrows_swallowedAsFailedUsage() {
        when(walletService.isEnabled()).thenReturn(true);
        when(pricingService.computeCost(any(), any(), any(), any(), any(), anyInt(), anyInt(),
                anyBoolean(), any(), any()))
                .thenReturn(new BigDecimal("0.003"));
        when(ratioService.toPoints(any())).thenReturn(new BigDecimal("0.3"));
        when(groupWalletService.chargeGroup(any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenThrow(new BusinessException(ErrorCode.INTERNAL_ERROR, "组池钱包行缺失"));

        assertThatCode(() -> billing.onSuccess(1L, 7L, "GLOBAL", "gpt-4", "CHAT", 100, 50,
                LlmUsageLogEntity.STATUS_SUCCESS, null, 5L))
                .doesNotThrowAnyException();

        verify(walletService, never()).charge(any(), any(), any(), any(), any());
        verify(usageCollector).record(eq(1L), any(), any(), any(), any(),
                any(), any(), eq(null), eq(null), eq(LlmUsageLogEntity.STATUS_FAILED), any());
    }

    /** gid 非空但 uid=null（系统调用带错参）→ 退回个人分支语义：charge(null) 短路仅采不扣。 */
    @Test
    void onSuccess_groupWithoutUser_personalNoopStillRecords() {
        when(walletService.isEnabled()).thenReturn(true);
        when(pricingService.computeCost(any(), any(), any(), any(), any(), anyInt(), anyInt(),
                anyBoolean(), any(), any()))
                .thenReturn(new BigDecimal("0.001"));
        when(ratioService.toPoints(any())).thenReturn(new BigDecimal("0.1"));

        billing.onSuccess(null, 7L, "GLOBAL", "embed-v1", "EMBED", 10, 0,
                LlmUsageLogEntity.STATUS_SUCCESS, null, 5L);

        verify(groupWalletService, never()).chargeGroup(any(), any(), any(), any(), any(), any(), anyBoolean());
        verify(usageCollector).record(eq(null), any(), any(), any(), eq("EMBED"),
                any(), any(), any(), any(), eq(LlmUsageLogEntity.STATUS_SUCCESS), any(),
                any(), any(), eq(5L), any());
    }

    // ---------- B3（Q4=B）：开局全额预扣 holdChat ----------

    /** est=输入估算+min(maxTokens,帽) 出量；可用<est → INSUFFICIENT_POINTS 且话术带两数。 */
    @Test
    void holdChat_insufficient_throwsWithBothNumbers() {
        enableChatHold();
        when(walletService.isEnabled()).thenReturn(true);
        when(pricingService.computeCost(eq("CHAT"), eq(7L), eq("gpt-4"),
                eq(500), eq(100), eq(0), eq(0))).thenReturn(new BigDecimal("0.01"));
        when(ratioService.toPoints(new BigDecimal("0.01"))).thenReturn(new BigDecimal("100"));
        when(walletService.getBalance(1L)).thenReturn(new BigDecimal("10"));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> billing.holdChat(1L, null, 7L, "gpt-4", 500, 100, "r1"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("100")
                .hasMessageContaining("10");
        verify(walletService, never()).chargeIdempotent(any(), any(), any(), any(), any(), any());
    }

    /** 可用充足 → 幂等键 chat-hold-{ref} 全额预扣，返回 est。 */
    @Test
    void holdChat_ok_chargesIdempotentWithRefKey() {
        enableChatHold();
        when(walletService.isEnabled()).thenReturn(true);
        when(pricingService.computeCost(eq("CHAT"), eq(7L), eq("gpt-4"),
                eq(500), eq(100), eq(0), eq(0))).thenReturn(new BigDecimal("0.01"));
        when(ratioService.toPoints(new BigDecimal("0.01"))).thenReturn(new BigDecimal("100"));
        when(walletService.getBalance(1L)).thenReturn(new BigDecimal("500"));
        when(walletService.chargeIdempotent(eq(1L), eq(new BigDecimal("100")), eq("CHAT-HOLD"),
                isNull(), any(), eq("chat-hold-r1"))).thenReturn(new BigDecimal("400"));

        BigDecimal held = billing.holdChat(1L, null, 7L, "gpt-4", 500, 100, "r1");

        assertThat(held).isEqualByComparingTo("100");
    }

    /** 组模式：可用=组池余额，预扣走 chargeGroup（CHAT-HOLD 腿）。 */
    @Test
    void holdChat_group_usesGroupBalanceAndChargeGroup() {
        enableChatHold();
        when(walletService.isEnabled()).thenReturn(true);
        when(pricingService.computeCost(eq("CHAT"), eq(7L), eq("gpt-4"),
                eq(500), eq(100), eq(0), eq(0))).thenReturn(new BigDecimal("0.01"));
        when(ratioService.toPoints(new BigDecimal("0.01"))).thenReturn(new BigDecimal("100"));
        when(groupWalletService.getGroupBalance(5L)).thenReturn(new BigDecimal("300"));
        when(groupWalletService.chargeGroup(eq(5L), eq(1L), eq(new BigDecimal("100")),
                eq("CHAT-HOLD"), eq("r1"), eq("chat-hold-r1"), eq(false))).thenReturn(new BigDecimal("200"));

        assertThat(billing.holdChat(1L, 5L, 7L, "gpt-4", 500, 100, "r1")).isEqualByComparingTo("100");
        verify(walletService, never()).chargeIdempotent(any(), any(), any(), any(), any(), any());
    }

    /** 开关关（默认 false）→ 短路不查 isEnabled/不估价不扣，返 null（网关走答完后扣现状）。 */
    @Test
    void holdChat_disabled_returnsNullNoPricing() {
        assertThat(billing.holdChat(1L, null, 7L, "gpt-4", 500, 100, "r1")).isNull();
        verify(pricingService, never()).computeCost(any(), any(), any(), any(), any(), anyInt(), anyInt());
    }

    // ---------- B3：正常尾结算 settleChatHeld（多退少补） ----------

    /** 实耗>预扣 → 补扣差额（幂等键 chat-settle-{ref}），usage+审计落 SUCCESS，返实耗。 */
    @Test
    void settleChatHeld_overEst_chargesDiff() {
        enableChatHold();
        when(pricingService.computeCost(eq("CHAT"), eq(7L), eq("gpt-4"),
                eq(600), eq(400), eq(0), eq(0), anyBoolean(), isNull(), isNull())).thenReturn(new BigDecimal("0.01"));
        when(ratioService.toPoints(new BigDecimal("0.01"))).thenReturn(new BigDecimal("500"));
        when(walletService.chargeIdempotent(eq(1L), eq(new BigDecimal("200")), eq("CHAT"),
                isNull(), any(), eq("chat-settle-r1"))).thenReturn(new BigDecimal("0"));

        BigDecimal actual = billing.settleChatHeld(1L, 7L, "GLOBAL", "gpt-4", 600, 400,
                LlmUsageLogEntity.STATUS_SUCCESS, null, null, "r1", new BigDecimal("300"));

        assertThat(actual).isEqualByComparingTo("500");
        verify(usageCollector).record(eq(1L), eq(7L), eq("GLOBAL"), eq("gpt-4"), eq("CHAT"),
                eq(600), eq(400), eq(new BigDecimal("0.01")), eq(new BigDecimal("500")),
                eq(LlmUsageLogEntity.STATUS_SUCCESS), any(), any(), any(), any(), any());
    }

    /** 实耗<预扣 → 退差额。 */
    @Test
    void settleChatHeld_underEst_refundsDiff() {
        enableChatHold();
        when(pricingService.computeCost(eq("CHAT"), eq(7L), eq("gpt-4"),
                eq(100), eq(50), eq(0), eq(0), anyBoolean(), isNull(), isNull())).thenReturn(new BigDecimal("0.001"));
        when(ratioService.toPoints(new BigDecimal("0.001"))).thenReturn(new BigDecimal("100"));
        when(walletService.refundIdempotent(eq(1L), eq(new BigDecimal("200")), eq("CHAT"),
                isNull(), any(), eq("chat-settle-r1"))).thenReturn(new BigDecimal("300"));

        BigDecimal actual = billing.settleChatHeld(1L, 7L, "GLOBAL", "gpt-4", 100, 50,
                LlmUsageLogEntity.STATUS_SUCCESS, null, null, "r1", new BigDecimal("300"));

        assertThat(actual).isEqualByComparingTo("100");
        verify(walletService, never()).chargeIdempotent(any(), any(), any(), any(), any(), any());
    }

    /** B5（Q10=A）：个人补扣失败（余额耗尽）→ 差额挂账 chargeToDebt，结算不落 FAILED、返实耗。 */
    @Test
    void settleChatHeld_personalTopupFail_fallsToDebt() {
        enableChatHold();
        when(pricingService.computeCost(eq("CHAT"), eq(7L), eq("gpt-4"),
                eq(600), eq(400), eq(0), eq(0), anyBoolean(), isNull(), isNull())).thenReturn(new BigDecimal("0.01"));
        when(ratioService.toPoints(new BigDecimal("0.01"))).thenReturn(new BigDecimal("500"));
        when(walletService.chargeIdempotent(any(), any(), any(), any(), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.INSUFFICIENT_POINTS));

        BigDecimal actual = billing.settleChatHeld(1L, 7L, "GLOBAL", "gpt-4", 600, 400,
                LlmUsageLogEntity.STATUS_SUCCESS, null, null, "r1", new BigDecimal("300"));

        assertThat(actual).isEqualByComparingTo("500");
        verify(walletService).chargeToDebt(eq(1L), eq(new BigDecimal("200")), eq("CHAT"),
                isNull(), any());
    }

    /** 组补差抛（V161 后资金不足已不抛，此为系统级错误）→ 吞掉记 FAILED usage、返预扣额（预扣在手）。 */
    @Test
    void settleChatHeld_groupSystemError_returnsHeld_V161() {
        enableChatHold();
        when(pricingService.computeCost(eq("CHAT"), eq(7L), eq("gpt-4"),
                eq(600), eq(400), eq(0), eq(0), anyBoolean(), isNull(), isNull())).thenReturn(new BigDecimal("0.01"));
        when(ratioService.toPoints(new BigDecimal("0.01"))).thenReturn(new BigDecimal("500"));
        when(groupWalletService.chargeGroup(any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenThrow(new BusinessException(ErrorCode.INTERNAL_ERROR, "组池钱包行缺失"));

        BigDecimal actual = billing.settleChatHeld(1L, 7L, "GLOBAL", "gpt-4", 600, 400,
                LlmUsageLogEntity.STATUS_SUCCESS, null, 5L, "r1", new BigDecimal("300"));

        assertThat(actual).isEqualByComparingTo("300");
        verify(usageCollector).record(eq(1L), eq(7L), eq("GLOBAL"), eq("gpt-4"), eq("CHAT"),
                eq(600), eq(400), eq(null), eq(null), eq(LlmUsageLogEntity.STATUS_FAILED), any());
    }

    // ---------- B3（Q3=B）：取消折算 settleChatCancelled ----------

    /** 已产字符折算 min(折算,预扣) 实扣、差额退（幂等键 chat-cancel-{ref}），usage 记 ESTIMATED。 */
    @Test
    void settleChatCancelled_byChars_refundsDiffAndRecordsEstimated() {
        enableChatHold();
        // 1600 字符 ÷1.6 = 1000 tokens → 折算积分 200；预扣 300 → 退 100
        when(pricingService.computeCost(eq("CHAT"), eq(7L), eq("gpt-4"),
                isNull(), eq(1000), eq(0), eq(0))).thenReturn(new BigDecimal("0.002"));
        when(ratioService.toPoints(new BigDecimal("0.002"))).thenReturn(new BigDecimal("200"));
        when(walletService.refundIdempotent(eq(1L), eq(new BigDecimal("100")), eq("CHAT-HOLD"),
                isNull(), any(), eq("chat-cancel-r1"))).thenReturn(new BigDecimal("50"));

        billing.settleChatCancelled(1L, 7L, "GLOBAL", "gpt-4", null, "r1",
                new BigDecimal("300"), 1600L, "s1");

        verify(usageCollector).record(eq(1L), eq(7L), eq("GLOBAL"), eq("gpt-4"), eq("CHAT"),
                isNull(), eq(1000), isNull(), eq(new BigDecimal("200")),
                eq(LlmUsageLogEntity.STATUS_ESTIMATED), eq("cancelled"), any(), eq("s1"), any());
    }

    /** 一字未产（provider 失败）→ 全额退、不落 usage。 */
    @Test
    void settleChatCancelled_zeroChars_fullRefundNoUsage() {
        enableChatHold();
        when(walletService.refundIdempotent(eq(1L), eq(new BigDecimal("300")), eq("CHAT-HOLD"),
                isNull(), any(), eq("chat-cancel-r1"))).thenReturn(new BigDecimal("300"));

        billing.settleChatCancelled(1L, 7L, "GLOBAL", "gpt-4", null, "r1",
                new BigDecimal("300"), 0L, null);

        verify(walletService).refundIdempotent(eq(1L), eq(new BigDecimal("300")), eq("CHAT-HOLD"),
                isNull(), any(), eq("chat-cancel-r1"));
        verify(usageCollector, never()).record(any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    /** 未预扣（开关关路径）→ 取消时本就没扣，直接短路。 */
    @Test
    void settleChatCancelled_noHold_noop() {
        billing.settleChatCancelled(1L, 7L, "GLOBAL", "gpt-4", null, "r1", null, 500L, null);
        verify(walletService, never()).refundIdempotent(any(), any(), any(), any(), any(), any());
    }

    // ==================== 9x-1（V160 D5）：缓存腿透传计价 + 落列 ====================

    @Test
    void onSuccess_cachedTokens_passedToPricingAndRecorded() {
        when(walletService.isEnabled()).thenReturn(true);
        // 10 参 computeCost（+hasReference/resolution/cachedTokens）收到 cached=40
        when(pricingService.computeCost(eq("CHAT"), anyLong(), eq("gpt-4"),
                eq(60), eq(8), eq(0), eq(0), eq(false), isNull(), eq(40L)))
                .thenReturn(new BigDecimal("0.002"));
        when(ratioService.toPoints(new BigDecimal("0.002"))).thenReturn(new BigDecimal("0.2"));
        when(walletService.charge(eq(1L), eq(new BigDecimal("0.2")), eq("CHAT"),
                eq(null), eq("gpt-4"))).thenReturn(new BigDecimal("99.8"));

        billing.onSuccess(1L, 7L, "GLOBAL", "gpt-4", "CHAT", 60, 8,
                LlmUsageLogEntity.STATUS_SUCCESS, null, null, 40L);

        // usage 行带 cachedTokens=40（15 参 record）
        verify(usageCollector).record(eq(1L), eq(7L), eq("GLOBAL"), eq("gpt-4"), eq("CHAT"),
                eq(60), eq(8), eq(new BigDecimal("0.002")), eq(new BigDecimal("0.2")),
                eq(LlmUsageLogEntity.STATUS_SUCCESS), eq(null), eq(null), eq(null), eq(null), eq(40L));
    }

    @Test
    void onSuccess_legacyOverload_cachedNull_twoLegSemantics() {
        when(walletService.isEnabled()).thenReturn(true);
        when(pricingService.computeCost(eq("CHAT"), anyLong(), eq("gpt-4"),
                eq(100), eq(50), eq(0), eq(0), eq(false), isNull(), isNull()))
                .thenReturn(new BigDecimal("0.003"));
        when(ratioService.toPoints(new BigDecimal("0.003"))).thenReturn(new BigDecimal("0.3"));
        when(walletService.charge(eq(1L), eq(new BigDecimal("0.3")), eq("CHAT"),
                eq(null), eq("gpt-4"))).thenReturn(new BigDecimal("99.7"));

        billing.onSuccess(1L, 7L, "GLOBAL", "gpt-4", "CHAT", 100, 50);

        verify(usageCollector).record(eq(1L), eq(7L), eq("GLOBAL"), eq("gpt-4"), eq("CHAT"),
                eq(100), eq(50), eq(new BigDecimal("0.003")), eq(new BigDecimal("0.3")),
                eq(LlmUsageLogEntity.STATUS_SUCCESS), eq(null), eq(null), eq(null), eq(null), isNull());
    }

    @Test
    void settleChatHeld_cachedTokens_settlePriceIncludesCacheLeg() {
        when(pricingService.computeCost(eq("CHAT"), anyLong(), eq("gpt-4"),
                eq(60), eq(8), eq(0), eq(0), eq(false), isNull(), eq(40L)))
                .thenReturn(new BigDecimal("0.002"));
        when(ratioService.toPoints(new BigDecimal("0.002"))).thenReturn(new BigDecimal("0.2"));

        BigDecimal actual = billing.settleChatHeld(1L, 7L, "GLOBAL", "gpt-4",
                60, 8, LlmUsageLogEntity.STATUS_SUCCESS, null, null, "r1", new BigDecimal("0.5"), 40L);

        assertThat(actual).isEqualByComparingTo("0.2");
        verify(usageCollector).record(eq(1L), eq(7L), eq("GLOBAL"), eq("gpt-4"), eq("CHAT"),
                eq(60), eq(8), eq(new BigDecimal("0.002")), eq(new BigDecimal("0.2")),
                eq(LlmUsageLogEntity.STATUS_SUCCESS), eq(null), eq(null), eq(null), eq(null), eq(40L));
    }
}
