package com.superprogrammer.billing.service;

import com.superprogrammer.billing.entity.IdempotencyKeyEntity;
import com.superprogrammer.billing.entity.PaymentOrderEntity;
import com.superprogrammer.billing.entity.PointsLedgerEntity;
import com.superprogrammer.billing.entity.UserPointsBalanceEntity;
import com.superprogrammer.billing.mapper.IdempotencyKeyMapper;
import com.superprogrammer.billing.mapper.PaymentOrderMapper;
import com.superprogrammer.billing.mapper.PointsLedgerMapper;
import com.superprogrammer.billing.mapper.UserPointsBalanceMapper;
import com.superprogrammer.common.audit.AuditLogEntity;
import com.superprogrammer.common.audit.AuditLogService;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PointsWalletService 单测：预检/扣/退/充分支 + 开关 + userId=null 短路。
 * <p>并发扣减的「不超支」需真 PG 行锁验证（Phase4 集成），本单测覆盖业务分支与流水正确性。
 */
@ExtendWith(MockitoExtension.class)
class PointsWalletServiceTest {

    @Mock
    private UserPointsBalanceMapper balanceMapper;
    @Mock
    private PointsLedgerMapper ledgerMapper;
    @Mock
    private PaymentOrderMapper paymentOrderMapper;
    @Mock
    private IdempotencyKeyMapper idempotencyKeyMapper;
    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private PointsWalletService wallet;

    @BeforeEach
    void enableBilling() {
        ReflectionTestUtils.setField(wallet, "enabled", true);
        ReflectionTestUtils.setField(wallet, "refundOnFail", true);
    }

    // ---------- requireAffordable ----------
    @Test
    void requireAffordable_zeroBalance_throws() {
        when(balanceMapper.selectByUserId(1L)).thenReturn(balance("0"));
        assertThatThrownBy(() -> wallet.requireAffordable(1L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo(ErrorCode.INSUFFICIENT_POINTS.getCode()));
    }

    @Test
    void requireAffordable_negativeBalance_throws() {
        // 负数欠款也拦
        when(balanceMapper.selectByUserId(1L)).thenReturn(balance("-49.00"));
        assertThatThrownBy(() -> wallet.requireAffordable(1L))
                .isInstanceOf(BusinessException.class);
    }

    // ---------- 安全体系 S1 · SEC-FR-120 SQL 守卫 ----------

    // AC-SEC-FR-120：并发透支被 SQL 守卫拦下（adjustBalanceReturn 0 行）→ INSUFFICIENT_POINTS，非 500
    @Test
    void charge_overdrawGuard_throwsInsufficient() {
        when(balanceMapper.adjustBalanceReturn(eq(1L), any())).thenReturn(null);

        assertThatThrownBy(() -> wallet.charge(1L, new BigDecimal("5.00"), "CHAT", null, "m"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo(ErrorCode.INSUFFICIENT_POINTS.getCode()));
    }

    // AC-SEC-FR-120：正向调整返 null 仍是「行缺失」防御性 500（语义不混）
    @Test
    void refund_nullReturn_stillInternalError() {
        when(balanceMapper.adjustBalanceReturn(eq(1L), any())).thenReturn(null);

        assertThatThrownBy(() -> wallet.refund(1L, new BigDecimal("5.00"), "CHAT", null, "m"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo(ErrorCode.INTERNAL_ERROR.getCode()));
    }

    @Test
    void requireAffordable_positiveBalance_passes() {
        when(balanceMapper.selectByUserId(1L)).thenReturn(balance("100.00"));
        assertThatCode(() -> wallet.requireAffordable(1L)).doesNotThrowAnyException();
    }

    @Test
    void requireAffordable_nullUser_skips() {
        // userId=null（系统调用）跳预检，不查库
        assertThatCode(() -> wallet.requireAffordable(null)).doesNotThrowAnyException();
        verify(balanceMapper, org.mockito.Mockito.never()).selectByUserId(any());
    }

    @Test
    void requireAffordable_disabled_skipsEvenZeroBalance() {
        ReflectionTestUtils.setField(wallet, "enabled", false);
        // enabled=false：短路返回，根本不查库（故不 stub selectByUserId）
        assertThatCode(() -> wallet.requireAffordable(1L)).doesNotThrowAnyException();
        verify(balanceMapper, org.mockito.Mockito.never()).selectByUserId(any());
    }

    // ---------- charge ----------

    @Test
    void charge_normal_insertsConsumeLedgerWithNegatedDelta() {
        BigDecimal points = new BigDecimal("50.00");
        BigDecimal after = new BigDecimal("50.00");
        when(balanceMapper.adjustBalanceReturn(eq(1L), eq(points.negate()))).thenReturn(after);

        BigDecimal result = wallet.charge(1L, points, PointsLedgerEntity.REF_CHAT, 99L, "对话扣费");

        assertThat(result).isEqualByComparingTo(after);
        ArgumentCaptor<PointsLedgerEntity> cap = ArgumentCaptor.forClass(PointsLedgerEntity.class);
        verify(ledgerMapper).insert(cap.capture());
        PointsLedgerEntity ledger = cap.getValue();
        assertThat(ledger.getType()).isEqualTo(PointsLedgerEntity.TYPE_CONSUME);
        assertThat(ledger.getDeltaPoints()).isEqualByComparingTo(points.negate()); // 负
        assertThat(ledger.getBalanceAfter()).isEqualByComparingTo(after);
        assertThat(ledger.getRefType()).isEqualTo(PointsLedgerEntity.REF_CHAT);
        assertThat(ledger.getRefId()).isEqualTo(99L);
        verify(balanceMapper).insertIfAbsent(1L);
    }

    @Test
    void charge_disabled_returnsNullNoSideEffect() {
        ReflectionTestUtils.setField(wallet, "enabled", false);
        BigDecimal result = wallet.charge(1L, new BigDecimal("50"), PointsLedgerEntity.REF_CHAT, 1L, "x");
        assertThat(result).isNull();
        verify(balanceMapper, org.mockito.Mockito.never()).adjustBalanceReturn(any(), any());
        verify(ledgerMapper, org.mockito.Mockito.never()).insert(any());
    }

    @Test
    void charge_nullUser_returnsNull() {
        assertThat(wallet.charge(null, new BigDecimal("50"), PointsLedgerEntity.REF_CHAT, 1L, "x")).isNull();
    }

    @Test
    void refund_insertsRefundLedgerWithPositiveDelta() {
        BigDecimal points = new BigDecimal("50.00");
        when(balanceMapper.adjustBalanceReturn(eq(1L), eq(points))).thenReturn(new BigDecimal("100.00"));

        wallet.refund(1L, points, PointsLedgerEntity.REF_CHAT, 99L, "失败退");

        ArgumentCaptor<PointsLedgerEntity> cap = ArgumentCaptor.forClass(PointsLedgerEntity.class);
        verify(ledgerMapper).insert(cap.capture());
        assertThat(cap.getValue().getType()).isEqualTo(PointsLedgerEntity.TYPE_REFUND);
        assertThat(cap.getValue().getDeltaPoints()).isEqualByComparingTo(points); // 正
    }

    // ---------- grant ----------

    @Test
    void grant_createsPaidOrderAndCreditsAdminGrantLedger() {
        BigDecimal points = new BigDecimal("1000.00");
        BigDecimal money = new BigDecimal("10.00");
        when(balanceMapper.adjustBalanceReturn(eq(1L), eq(points))).thenReturn(points);

        BigDecimal result = wallet.grant(1L, points, money, PaymentOrderEntity.CHANNEL_ADMIN, null);

        assertThat(result).isEqualByComparingTo(points);
        ArgumentCaptor<PaymentOrderEntity> ord = ArgumentCaptor.forClass(PaymentOrderEntity.class);
        verify(paymentOrderMapper).insert(ord.capture());
        assertThat(ord.getValue().getStatus()).isEqualTo(PaymentOrderEntity.STATUS_PAID);
        assertThat(ord.getValue().getChannel()).isEqualTo(PaymentOrderEntity.CHANNEL_ADMIN);
        ArgumentCaptor<PointsLedgerEntity> led = ArgumentCaptor.forClass(PointsLedgerEntity.class);
        verify(ledgerMapper).insert(led.capture());
        assertThat(led.getValue().getType()).isEqualTo(PointsLedgerEntity.TYPE_ADMIN_GRANT);
        assertThat(led.getValue().getRefType()).isEqualTo(PointsLedgerEntity.REF_PAYMENT);
    }

    @Test
    void grant_runsEvenWhenBillingDisabled() {
        // grant 不看 billing.enabled（运维核心能力恒开）
        ReflectionTestUtils.setField(wallet, "enabled", false);
        BigDecimal points = new BigDecimal("100");
        when(balanceMapper.adjustBalanceReturn(eq(1L), eq(points))).thenReturn(points);
        wallet.grant(1L, points, new BigDecimal("1"), PaymentOrderEntity.CHANNEL_ADMIN, null);
        verify(paymentOrderMapper).insert(any());
    }

    @Test
    void grant_nullUser_throws() {
        assertThatThrownBy(() -> wallet.grant(null, new BigDecimal("1"), null, null, null))
                .isInstanceOf(BusinessException.class);
    }

    // ---------- 安全体系 S1 · SEC-FR-121 幂等键防重放 ----------

    // AC-SEC-FR-121：首次占位成功 → 正常扣减 + 回填 result_ref（首次流水 id）
    @Test
    void chargeIdempotent_firstOccupy_chargesAndBackfillsResultRef() {
        BigDecimal points = new BigDecimal("50.00");
        BigDecimal after = new BigDecimal("50.00");
        when(idempotencyKeyMapper.tryOccupy("k1", 1L, "billing.charge")).thenReturn(1);
        when(balanceMapper.adjustBalanceReturn(eq(1L), eq(points.negate()))).thenReturn(after);
        when(ledgerMapper.insert(any())).thenAnswer(inv -> {
            ((PointsLedgerEntity) inv.getArgument(0)).setId(42L);
            return 1;
        });

        BigDecimal result = wallet.chargeIdempotent(1L, points, PointsLedgerEntity.REF_CHAT, 99L, "m", "k1");

        assertThat(result).isEqualByComparingTo(after);
        verify(ledgerMapper).insert(any());                       // 真扣了一次
        verify(idempotencyKeyMapper).updateResultRef("k1", "42"); // 回填首次流水 id
    }

    // AC-SEC-FR-121：同键重复提交 → 不再扣，返回首次结果（相同 balanceAfter）
    @Test
    void chargeIdempotent_duplicateKey_returnsFirstResultWithoutRecharging() {
        IdempotencyKeyEntity existing = new IdempotencyKeyEntity();
        existing.setIdemKey("k1");
        existing.setUserId(1L);
        existing.setScope("billing.charge");
        existing.setResultRef("42");
        PointsLedgerEntity first = new PointsLedgerEntity();
        first.setId(42L);
        first.setDeltaPoints(new BigDecimal("-50.00"));
        first.setBalanceAfter(new BigDecimal("50.00"));
        when(idempotencyKeyMapper.tryOccupy("k1", 1L, "billing.charge")).thenReturn(0);
        when(idempotencyKeyMapper.selectByKey("k1")).thenReturn(existing);
        when(ledgerMapper.selectById(42L)).thenReturn(first);

        BigDecimal result = wallet.chargeIdempotent(1L, new BigDecimal("50.00"),
                PointsLedgerEntity.REF_CHAT, 99L, "m", "k1");

        assertThat(result).isEqualByComparingTo("50.00"); // 首次结果原样返回
        verify(balanceMapper, org.mockito.Mockito.never()).adjustBalanceReturn(any(), any()); // 未再扣
        verify(ledgerMapper, org.mockito.Mockito.never()).insert(any());
        verify(auditLogService, org.mockito.Mockito.never()).record(any()); // 金额一致不审计
    }

    // AC-SEC-FR-121：同键不同金额 = 疑似重放/篡改 → 写安全审计（仍返回首次结果，调用方无感）
    @Test
    void chargeIdempotent_sameKeyDifferentAmount_auditsAndReturnsFirst() {
        IdempotencyKeyEntity existing = new IdempotencyKeyEntity();
        existing.setIdemKey("k1");
        existing.setUserId(1L);
        existing.setScope("billing.charge");
        existing.setResultRef("42");
        PointsLedgerEntity first = new PointsLedgerEntity();
        first.setId(42L);
        first.setDeltaPoints(new BigDecimal("-50.00"));
        first.setBalanceAfter(new BigDecimal("50.00"));
        when(idempotencyKeyMapper.tryOccupy("k1", 1L, "billing.charge")).thenReturn(0);
        when(idempotencyKeyMapper.selectByKey("k1")).thenReturn(existing);
        when(ledgerMapper.selectById(42L)).thenReturn(first);
        when(auditLogService.fromMdc(eq("billing"), eq("idempotency_conflict"), any(), eq("k1"), any(), eq("FAIL")))
                .thenReturn(new AuditLogEntity());

        BigDecimal result = wallet.chargeIdempotent(1L, new BigDecimal("1.00"), // 金额被改
                PointsLedgerEntity.REF_CHAT, 99L, "m", "k1");

        assertThat(result).isEqualByComparingTo("50.00");
        verify(auditLogService).record(any(AuditLogEntity.class));
        verify(balanceMapper, org.mockito.Mockito.never()).adjustBalanceReturn(any(), any());
    }

    // AC-SEC-FR-121：占位居中但流水缺失（result_ref 空）→ CONFLICT 让调用方重试
    @Test
    void chargeIdempotent_occupiedButNoResult_throwsConflict() {
        IdempotencyKeyEntity existing = new IdempotencyKeyEntity();
        existing.setIdemKey("k1"); // resultRef = null
        existing.setUserId(1L);
        existing.setScope("billing.charge");
        when(idempotencyKeyMapper.tryOccupy("k1", 1L, "billing.charge")).thenReturn(0);
        when(idempotencyKeyMapper.selectByKey("k1")).thenReturn(existing);

        assertThatThrownBy(() -> wallet.chargeIdempotent(1L, new BigDecimal("50.00"),
                PointsLedgerEntity.REF_CHAT, 99L, "m", "k1"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo(ErrorCode.CONFLICT.getCode()));
        verify(balanceMapper, org.mockito.Mockito.never()).adjustBalanceReturn(any(), any());
    }

    // AC-SEC-FR-121：键为空 → 退化为普通扣减（不占位）
    @Test
    void chargeIdempotent_blankKey_delegatesToPlainCharge() {
        BigDecimal points = new BigDecimal("50.00");
        BigDecimal after = new BigDecimal("50.00");
        when(balanceMapper.adjustBalanceReturn(eq(1L), eq(points.negate()))).thenReturn(after);

        BigDecimal result = wallet.chargeIdempotent(1L, points, PointsLedgerEntity.REF_CHAT, 99L, "m", "  ");

        assertThat(result).isEqualByComparingTo(after);
        verify(idempotencyKeyMapper, org.mockito.Mockito.never()).tryOccupy(any(), any(), any());
    }

    // AC-SEC-FR-121：幂等充值首次占位 → 建单 + 充 + 回填
    @Test
    void grantIdempotent_firstOccupy_grantsAndBackfills() {
        BigDecimal points = new BigDecimal("1000.00");
        when(idempotencyKeyMapper.tryOccupy("g1", 1L, "billing.grant")).thenReturn(1);
        when(balanceMapper.adjustBalanceReturn(eq(1L), eq(points))).thenReturn(points);
        when(ledgerMapper.insert(any())).thenAnswer(inv -> {
            ((PointsLedgerEntity) inv.getArgument(0)).setId(7L);
            return 1;
        });

        BigDecimal result = wallet.grantIdempotent(1L, points, null,
                PaymentOrderEntity.CHANNEL_ADMIN, null, "g1");

        assertThat(result).isEqualByComparingTo(points);
        verify(paymentOrderMapper).insert(any());
        verify(idempotencyKeyMapper).updateResultRef("g1", "7");
    }

    // Phase4 审查修正（opus 资金🔴）：撞键身份核验——键全局唯一，跨用户同键 = 疑似重放/伪造，
    // 绝不回返首次结果（含他人 balanceAfter = 跨用户余额泄露信道）→ 审计 + CONFLICT
    @Test
    void chargeIdempotent_collidingKeyDifferentUser_throwsConflictAndAudits() {
        IdempotencyKeyEntity existing = new IdempotencyKeyEntity();
        existing.setIdemKey("k1");
        existing.setUserId(2L); // 键属他人
        existing.setScope("billing.charge");
        existing.setResultRef("42");
        when(idempotencyKeyMapper.tryOccupy("k1", 1L, "billing.charge")).thenReturn(0);
        when(idempotencyKeyMapper.selectByKey("k1")).thenReturn(existing);
        when(auditLogService.fromMdc(eq("billing"), eq("idempotency_conflict"), any(), eq("k1"), any(), eq("FAIL")))
                .thenReturn(new AuditLogEntity());

        assertThatThrownBy(() -> wallet.chargeIdempotent(1L, new BigDecimal("50.00"),
                PointsLedgerEntity.REF_CHAT, 99L, "m", "k1"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo(ErrorCode.CONFLICT.getCode()));
        verify(auditLogService).record(any(AuditLogEntity.class));
        verify(balanceMapper, org.mockito.Mockito.never()).adjustBalanceReturn(any(), any()); // 绝不扣也不回查返结果
        verify(ledgerMapper, org.mockito.Mockito.never()).selectById(any());
    }

    // 同上：同用户但跨 scope 同键（grant 的键拿到 charge 用）= 伪造 → CONFLICT + 审计
    @Test
    void chargeIdempotent_collidingKeyDifferentScope_throwsConflictAndAudits() {
        IdempotencyKeyEntity existing = new IdempotencyKeyEntity();
        existing.setIdemKey("k1");
        existing.setUserId(1L);
        existing.setScope("billing.grant"); // 键属另一 scope
        existing.setResultRef("42");
        when(idempotencyKeyMapper.tryOccupy("k1", 1L, "billing.charge")).thenReturn(0);
        when(idempotencyKeyMapper.selectByKey("k1")).thenReturn(existing);
        when(auditLogService.fromMdc(eq("billing"), eq("idempotency_conflict"), any(), eq("k1"), any(), eq("FAIL")))
                .thenReturn(new AuditLogEntity());

        assertThatThrownBy(() -> wallet.chargeIdempotent(1L, new BigDecimal("50.00"),
                PointsLedgerEntity.REF_CHAT, 99L, "m", "k1"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo(ErrorCode.CONFLICT.getCode()));
        verify(auditLogService).record(any(AuditLogEntity.class));
        verify(balanceMapper, org.mockito.Mockito.never()).adjustBalanceReturn(any(), any());
    }

    private UserPointsBalanceEntity balance(String points) {
        UserPointsBalanceEntity b = new UserPointsBalanceEntity();
        b.setUserId(1L);
        b.setBalancePoints(new BigDecimal(points));
        return b;
    }

    // ---------- B5（Q10=A）：欠款拦截 / 扣尽挂账 / 充值冲抵 ----------

    /** 欠款>0 → 拦全部个人消费入口，话术带欠款数与「充值后自动偿还」。 */
    @Test
    void requireAffordable_debtPositive_blocksWithMessage() {
        UserPointsBalanceEntity b = balance("100.00");
        b.setDebtPoints(new BigDecimal("50.00"));
        when(balanceMapper.selectByUserId(1L)).thenReturn(b);

        assertThatThrownBy(() -> wallet.requireAffordable(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("50")
                .hasMessageContaining("自动偿还");
    }

    /** 扣尽挂账：余额30 扣100 → 实付30（CONSUME 腿）+ 挂账70（DEBT 腿 delta=0 不动 Σdelta）。 */
    @Test
    void chargeToDebt_partialPay_consumeLegPlusDebtLeg() {
        ReflectionTestUtils.setField(wallet, "debtCollectEnabled", true);
        when(balanceMapper.selectByUserIdForUpdate(1L)).thenReturn(balance("30.00"));
        when(balanceMapper.adjustBalanceReturn(eq(1L), eq(new BigDecimal("-30.00"))))
                .thenReturn(BigDecimal.ZERO);
        when(balanceMapper.adjustDebtReturn(eq(1L), eq(new BigDecimal("70.00"))))
                .thenReturn(new BigDecimal("70.00"));

        wallet.chargeToDebt(1L, new BigDecimal("100.00"), PointsLedgerEntity.REF_CHAT, null, "补扣");

        java.util.List<PointsLedgerEntity> legs = capturedLegs(2);
        assertThat(legs.get(0).getType()).isEqualTo(PointsLedgerEntity.TYPE_CONSUME);
        assertThat(legs.get(0).getDeltaPoints()).isEqualByComparingTo("-30.00");
        assertThat(legs.get(1).getType()).isEqualTo(PointsLedgerEntity.TYPE_DEBT);
        assertThat(legs.get(1).getDeltaPoints()).isEqualByComparingTo("0.00");
        assertThat(legs.get(1).getRemark()).contains("70");
    }

    /** 余额够付（调用方兜底路径误入）→ 全额 CONSUME，不挂账。 */
    @Test
    void chargeToDebt_balanceCovers_noDebtLeg() {
        ReflectionTestUtils.setField(wallet, "debtCollectEnabled", true);
        when(balanceMapper.selectByUserIdForUpdate(1L)).thenReturn(balance("200.00"));
        when(balanceMapper.adjustBalanceReturn(eq(1L), eq(new BigDecimal("-100.00"))))
                .thenReturn(new BigDecimal("100.00"));

        wallet.chargeToDebt(1L, new BigDecimal("100.00"), PointsLedgerEntity.REF_CHAT, null, "补扣");

        java.util.List<PointsLedgerEntity> legs = capturedLegs(1);
        assertThat(legs.get(0).getType()).isEqualTo(PointsLedgerEntity.TYPE_CONSUME);
        verify(balanceMapper, org.mockito.Mockito.never()).adjustDebtReturn(any(), any());
    }

    /** 开关关 → 不动任何账（回到 FAILED usage 平台担损现状）。 */
    @Test
    void chargeToDebt_disabled_noop() {
        ReflectionTestUtils.setField(wallet, "debtCollectEnabled", false);
        wallet.chargeToDebt(1L, new BigDecimal("100.00"), PointsLedgerEntity.REF_CHAT, null, "补扣");
        verify(balanceMapper, org.mockito.Mockito.never()).adjustBalanceReturn(any(), any());
        verify(ledgerMapper, org.mockito.Mockito.never()).insert(any());
    }

    /** 挂账异常吞掉不外抛（调用方已在吞异常上下文）。 */
    @Test
    void chargeToDebt_exceptionSwallowed() {
        ReflectionTestUtils.setField(wallet, "debtCollectEnabled", true);
        when(balanceMapper.selectByUserIdForUpdate(1L)).thenThrow(new RuntimeException("db down"));
        assertThatCode(() -> wallet.chargeToDebt(1L, new BigDecimal("100.00"),
                PointsLedgerEntity.REF_CHAT, null, "补扣")).doesNotThrowAnyException();
    }

    /** 充值冲抵：欠50 充100 → DEBT_REPAY 腿50 + 主腿只入余额50（保 Σdelta=balance 恒等式）。 */
    @Test
    void grant_withDebt_repaysFirstThenCreditsRemainder() {
        UserPointsBalanceEntity b = balance("0.00");
        b.setDebtPoints(new BigDecimal("50.00"));
        when(balanceMapper.selectByUserIdForUpdate(1L)).thenReturn(b);
        when(balanceMapper.adjustDebtReturn(eq(1L), eq(new BigDecimal("-50.00"))))
                .thenReturn(BigDecimal.ZERO);
        when(balanceMapper.adjustBalanceReturn(eq(1L), eq(new BigDecimal("50.00"))))
                .thenReturn(new BigDecimal("50.00"));

        wallet.grant(1L, new BigDecimal("100.00"), new BigDecimal("10.00"),
                PaymentOrderEntity.CHANNEL_ADMIN, null);

        java.util.List<PointsLedgerEntity> legs = capturedLegs(2);
        assertThat(legs.get(0).getType()).isEqualTo(PointsLedgerEntity.TYPE_DEBT_REPAY);
        assertThat(legs.get(0).getDeltaPoints()).isEqualByComparingTo("0.00");
        assertThat(legs.get(1).getType()).isEqualTo(PointsLedgerEntity.TYPE_ADMIN_GRANT);
        assertThat(legs.get(1).getDeltaPoints()).isEqualByComparingTo("50.00");
        assertThat(legs.get(1).getRemark()).contains("冲抵欠款 50");
    }

    /** 无欠款充值：无 DEBT_REPAY 腿，主腿全额入账（现状不变）。 */
    @Test
    void grant_noDebt_singleFullLeg() {
        when(balanceMapper.selectByUserIdForUpdate(1L)).thenReturn(balance("0.00"));
        when(balanceMapper.adjustBalanceReturn(eq(1L), eq(new BigDecimal("100.00"))))
                .thenReturn(new BigDecimal("100.00"));

        wallet.grant(1L, new BigDecimal("100.00"), new BigDecimal("10.00"),
                PaymentOrderEntity.CHANNEL_ADMIN, null);

        java.util.List<PointsLedgerEntity> legs = capturedLegs(1);
        assertThat(legs.get(0).getType()).isEqualTo(PointsLedgerEntity.TYPE_ADMIN_GRANT);
        assertThat(legs.get(0).getDeltaPoints()).isEqualByComparingTo("100.00");
    }

    /** 自助充值回调同样先冲抵：欠100 充100 → 主腿 delta=0 仅留单据痕，余额不动。 */
    @Test
    void creditRechargeForOrder_fullSwallowedByDebt() {
        UserPointsBalanceEntity b = balance("0.00");
        b.setDebtPoints(new BigDecimal("100.00"));
        when(balanceMapper.selectByUserIdForUpdate(1L)).thenReturn(b);
        when(balanceMapper.adjustDebtReturn(eq(1L), eq(new BigDecimal("-100.00"))))
                .thenReturn(BigDecimal.ZERO);
        when(balanceMapper.adjustBalanceReturn(eq(1L), eq(new BigDecimal("0.00"))))
                .thenReturn(new BigDecimal("0.00"));

        BigDecimal after = wallet.creditRechargeForOrder(1L, new BigDecimal("100.00"),
                new BigDecimal("10.00"), 88L, null);

        assertThat(after).isEqualByComparingTo(BigDecimal.ZERO);
        java.util.List<PointsLedgerEntity> legs = capturedLegs(2);
        assertThat(legs.get(0).getType()).isEqualTo(PointsLedgerEntity.TYPE_DEBT_REPAY);
        assertThat(legs.get(1).getType()).isEqualTo(PointsLedgerEntity.TYPE_RECHARGE);
        assertThat(legs.get(1).getDeltaPoints()).isEqualByComparingTo("0.00");
    }

    /** 捕获 n 条流水并按插入序断言用。 */
    private java.util.List<PointsLedgerEntity> capturedLegs(int expected) {
        ArgumentCaptor<PointsLedgerEntity> cap = ArgumentCaptor.forClass(PointsLedgerEntity.class);
        verify(ledgerMapper, org.mockito.Mockito.times(expected)).insert(cap.capture());
        return cap.getAllValues();
    }
}
