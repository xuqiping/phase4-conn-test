package com.superprogrammer.billing.service;

import com.superprogrammer.billing.dto.ReconcileDiffVO;
import com.superprogrammer.billing.mapper.PointsLedgerMapper;
import com.superprogrammer.common.audit.AuditLogEntity;
import com.superprogrammer.common.audit.AuditLogService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 安全体系 S1 · SEC-FR-123 对账服务单测：全平无告警 / 差异行逐行审计+返回。
 * <p>真 SQL（FULL OUTER JOIN 差异抓取）由 BillingReconcileIT 验真 PG。
 */
@ExtendWith(MockitoExtension.class)
class BillingReconcileServiceTest {

    @Mock
    private PointsLedgerMapper ledgerMapper;
    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private BillingReconcileService reconcile;

    // AC-SEC-FR-123：全平 → 空差异，不写审计
    @Test
    void reconcile_allBalanced_noAudit() {
        when(ledgerMapper.findBalanceDiffs()).thenReturn(List.of());

        List<ReconcileDiffVO> diffs = reconcile.reconcile();

        assertThat(diffs).isEmpty();
        verify(auditLogService, never()).record(any());
    }

    // AC-SEC-FR-123：差异行 → 返回 + 逐行写安全审计(billing/reconcile_diff)
    @Test
    void reconcile_diffRow_auditsAndReturns() {
        ReconcileDiffVO d = new ReconcileDiffVO();
        d.setUserId(7L);
        d.setBalancePoints(new BigDecimal("100.00"));
        d.setLedgerSum(new BigDecimal("80.00"));
        d.setDiffPoints(new BigDecimal("20.00"));
        when(ledgerMapper.findBalanceDiffs()).thenReturn(List.of(d));
        when(auditLogService.fromMdc(eq("billing"), eq("reconcile_diff"),
                eq("wallet"), eq("7"), anyString(), eq("FAIL")))
                .thenReturn(new AuditLogEntity());

        List<ReconcileDiffVO> diffs = reconcile.reconcile();

        assertThat(diffs).hasSize(1);
        assertThat(diffs.get(0).getDiffPoints()).isEqualByComparingTo("20.00");
        verify(auditLogService).record(any(AuditLogEntity.class));
    }

    // 审计落库异常被吞掉，不阻断对账返回（审计绝不能成为新故障面）
    @Test
    void reconcile_auditThrows_swallowed() {
        ReconcileDiffVO d = new ReconcileDiffVO();
        d.setUserId(7L);
        d.setBalancePoints(BigDecimal.ONE);
        d.setLedgerSum(BigDecimal.ZERO);
        d.setDiffPoints(BigDecimal.ONE);
        when(ledgerMapper.findBalanceDiffs()).thenReturn(List.of(d));
        when(auditLogService.fromMdc(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("audit down"));

        List<ReconcileDiffVO> diffs = reconcile.reconcile();

        assertThat(diffs).hasSize(1);
    }
}
