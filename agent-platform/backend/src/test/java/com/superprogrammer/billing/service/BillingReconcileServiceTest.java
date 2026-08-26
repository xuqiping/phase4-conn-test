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
import static org.mockito.ArgumentMatchers.isNull;
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
    @Mock
    private com.superprogrammer.billing.mapper.GroupReconcileMapper groupReconcileMapper;

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

    // ==================== D4（20x-3）：组池划拨对账 ====================

    /** raw 行构造（null sum 即 mapper COALESCE 语义之外的手拼防御路径，仍应按 0 处理）。 */
    private static com.superprogrammer.billing.dto.GroupReconcileRawVO raw(
            long groupId, String name, String balance,
            String alloc, String reclaim, String consume, String refund, String personalNetOut) {
        return new com.superprogrammer.billing.dto.GroupReconcileRawVO(groupId, name,
                balance == null ? null : new BigDecimal(balance),
                alloc == null ? null : new BigDecimal(alloc),
                reclaim == null ? null : new BigDecimal(reclaim),
                consume == null ? null : new BigDecimal(consume),
                refund == null ? null : new BigDecimal(refund),
                personalNetOut == null ? null : new BigDecimal(personalNetOut));
    }

    // 全平：恒等式 + 双账本交叉均 0 → balanced=true、无异常行、不写审计
    @Test
    void groupReconcile_allBalanced() {
        // 组1：净额70(100−30) + 退款5 − 消耗20 = 期望55 = 余额55；个人净流出70 = 组净额70
        when(groupReconcileMapper.selectGroupRawRows(isNull())).thenReturn(List.of(
                raw(1, "A 组", "55", "100", "-30", "-20", "5", "70"),
                raw(2, "空组", "0", null, null, null, null, null)));

        var vo = reconcile.groupReconcile();

        assertThat(vo.balanced()).isTrue();
        assertThat(vo.abnormalGroups()).isEmpty();
        assertThat(vo.groups()).isNull();                        // 默认口径：groups 不填（Q9=A 原状）
        assertThat(vo.totals().netAllocated()).isEqualByComparingTo("70");
        assertThat(vo.totals().balance()).isEqualByComparingTo("55");
        assertThat(vo.totals().diff()).isEqualByComparingTo("0");
        verify(auditLogService, never()).record(any());
    }

    // 恒等式不平（组池比流水少 10）→ 该组入异常行 + 写审计；合计 diff 累计
    @Test
    void groupReconcile_identityDiff_flagsAndAudits() {
        when(groupReconcileMapper.selectGroupRawRows(isNull())).thenReturn(List.of(
                raw(3, "B 组", "90", "100", "0", "0", "0", "100")));
        when(auditLogService.fromMdc(eq("billing"), eq("group_reconcile_diff"),
                eq("project_group"), eq("3"), anyString(), eq("FAIL")))
                .thenReturn(new AuditLogEntity());

        var vo = reconcile.groupReconcile();

        assertThat(vo.balanced()).isFalse();
        assertThat(vo.abnormalGroups()).hasSize(1);
        var row = vo.abnormalGroups().get(0);
        assertThat(row.expected()).isEqualByComparingTo("100");
        assertThat(row.diff()).isEqualByComparingTo("-10");
        assertThat(row.crossDiff()).isEqualByComparingTo("0");
        assertThat(vo.totals().diff()).isEqualByComparingTo("-10");
        verify(auditLogService).record(any(AuditLogEntity.class));
    }

    // 双账本交叉不平（组账本净额 100 vs 个人账本净流出 60）→ 该组入异常行
    @Test
    void groupReconcile_crossBookDiff_flags() {
        when(groupReconcileMapper.selectGroupRawRows(isNull())).thenReturn(List.of(
                raw(4, "C 组", "100", "100", "0", "0", "0", "60")));
        when(auditLogService.fromMdc(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new AuditLogEntity());

        var vo = reconcile.groupReconcile();

        assertThat(vo.balanced()).isFalse();
        assertThat(vo.abnormalGroups()).hasSize(1);
        assertThat(vo.abnormalGroups().get(0).diff()).isEqualByComparingTo("0");
        assertThat(vo.abnormalGroups().get(0).crossDiff()).isEqualByComparingTo("40");
    }

    // 【钉死假警报坑】SQL type 白名单只含四类资金腿——MEMBER_*/BACKSTOP/ADMIN_ADJUST 混入等式会永久报不平
    @Test
    void groupReconcileSql_whitelistExcludesNonFundingLegs() throws Exception {
        String sql = com.superprogrammer.billing.mapper.GroupReconcileMapper.class
                .getMethod("selectGroupRawRows", Long.class)
                .getAnnotation(org.apache.ibatis.annotations.Select.class)
                .value()[0];
        assertThat(sql).contains("IN ('ALLOCATE', 'RECLAIM', 'CONSUME', 'REFUND')");
        // 组流水侧白名单绝不能混入非资金腿
        assertThat(sql).doesNotContain("MEMBER_");
        assertThat(sql).doesNotContain("BACKSTOP");
        assertThat(sql).doesNotContain("ADMIN_ADJUST");
        // 个人账本交叉腿只取 GROUP_ALLOCATE/GROUP_RECLAIM
        assertThat(sql).contains("IN ('GROUP_ALLOCATE', 'GROUP_RECLAIM')");
        // 7x-1 下钻：groupId 动态过滤在（防 SQL 静态化后丢条件）
        assertThat(sql).contains("groupId != null");
    }

    // ==================== 7x-1（A4）：按组下钻 / 全组视图 ====================

    // groupId 选中 → mapper 按组过滤；groups=该组行（含平组）；totals=该组聚合
    @Test
    void groupReconcile_groupId_scopesRowsAndTotals() {
        when(groupReconcileMapper.selectGroupRawRows(eq(7L))).thenReturn(List.of(
                raw(7, "选中组", "55", "100", "-30", "-20", "5", "70")));

        var vo = reconcile.groupReconcile(7L, false);

        verify(groupReconcileMapper).selectGroupRawRows(eq(7L));
        assertThat(vo.groups()).hasSize(1);
        assertThat(vo.groups().get(0).groupId()).isEqualTo(7L);
        assertThat(vo.groups().get(0).diff()).isEqualByComparingTo("0");
        assertThat(vo.balanced()).isTrue();
        // totals=该组聚合（非全平台）
        assertThat(vo.totals().netAllocated()).isEqualByComparingTo("70");
        assertThat(vo.totals().balance()).isEqualByComparingTo("55");
        assertThat(vo.abnormalGroups()).isEmpty();
    }

    // includeAll 且无 groupId → groups=全组行含平组（空组也进）
    @Test
    void groupReconcile_includeAll_listsBalancedGroups() {
        when(groupReconcileMapper.selectGroupRawRows(isNull())).thenReturn(List.of(
                raw(1, "A 组", "55", "100", "-30", "-20", "5", "70"),
                raw(2, "空组", "0", null, null, null, null, null)));

        var vo = reconcile.groupReconcile(null, true);

        assertThat(vo.groups()).hasSize(2);                      // 含平组
        assertThat(vo.groups()).extracting(r -> r.groupId()).containsExactly(1L, 2L);
        assertThat(vo.balanced()).isTrue();
        assertThat(vo.totals().balance()).isEqualByComparingTo("55");
    }

    // groupId 未命中（组已删/不存在）→ 空行 + totals 全 0，balanced=true
    @Test
    void groupReconcile_groupIdMiss_emptyScope() {
        when(groupReconcileMapper.selectGroupRawRows(eq(99L))).thenReturn(List.of());

        var vo = reconcile.groupReconcile(99L, false);

        assertThat(vo.groups()).isEmpty();
        assertThat(vo.balanced()).isTrue();
        assertThat(vo.totals().balance()).isEqualByComparingTo("0");
    }

    // groupId 与 includeAll 同时给 → 单组优先（mapper 只收 groupId）
    @Test
    void groupReconcile_groupIdTakesPrecedenceOverIncludeAll() {
        when(groupReconcileMapper.selectGroupRawRows(eq(7L))).thenReturn(List.of(
                raw(7, "选中组", "55", "100", "-30", "-20", "5", "70")));

        reconcile.groupReconcile(7L, true);

        verify(groupReconcileMapper).selectGroupRawRows(eq(7L));
    }
}
