package com.superprogrammer.billing.service;

import com.superprogrammer.billing.entity.PaymentOrderEntity;
import com.superprogrammer.billing.entity.PointsLedgerEntity;
import com.superprogrammer.billing.entity.UserPointsBalanceEntity;
import com.superprogrammer.billing.mapper.PaymentOrderMapper;
import com.superprogrammer.billing.mapper.PointsLedgerMapper;
import com.superprogrammer.billing.mapper.UserPointsBalanceMapper;
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

    private UserPointsBalanceEntity balance(String points) {
        UserPointsBalanceEntity b = new UserPointsBalanceEntity();
        b.setUserId(1L);
        b.setBalancePoints(new BigDecimal(points));
        return b;
    }
}
