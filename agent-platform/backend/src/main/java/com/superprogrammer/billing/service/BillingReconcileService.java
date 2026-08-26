package com.superprogrammer.billing.service;

import com.superprogrammer.billing.dto.ReconcileDiffVO;
import com.superprogrammer.billing.mapper.PointsLedgerMapper;
import com.superprogrammer.common.audit.AuditLogEntity;
import com.superprogrammer.common.audit.AuditLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 计费对账服务（安全体系 S1 · SEC-FR-123 · L4）。
 * <p>不变量：用户余额 = Σ其全部流水 delta（余额只经 {@code PointsWalletService.adjust} 变动，
 * 每变必落流水，且 V80 已 REVOKE 应用账号对 points_ledger 的 UPDATE/DELETE——流水只增不改）。
 * <p>每日凌晨全量对账：差异行 = 疑似绕过统一入口直改余额 / 流水被删改，
 * 逐行写安全审计（billing/reconcile_diff）+ ERROR 日志告警。
 * <p>对账本身只读，绝不自动修账（修账须人工，防对账程序成为新的篡改面）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BillingReconcileService {

    private final PointsLedgerMapper ledgerMapper;
    private final AuditLogService auditLogService;
    private final com.superprogrammer.billing.mapper.PaymentOrderMapper paymentOrderMapper;
    /** D4（20x-3）：组池对账聚合。 */
    private final com.superprogrammer.billing.mapper.GroupReconcileMapper groupReconcileMapper;

    /**
     * 支付渠道异常三节（7x#3 运维入口，只读）：PENDING 超 10min 未关 / PAID 无流水 / 终态后仍付款。
     * 任一节非空打 WARN（补单/排查线索）；人工补单本期走 SQL+审计，不做页面。
     */
    public com.superprogrammer.billing.dto.PaymentAnomalyVO paymentAnomalies() {
        var vo = new com.superprogrammer.billing.dto.PaymentAnomalyVO(
                paymentOrderMapper.selectStalePending(10),
                paymentOrderMapper.selectPaidNoLedger(),
                paymentOrderMapper.selectClosedButPaid());
        if (!vo.stalePending().isEmpty() || !vo.paidNoLedger().isEmpty() || !vo.closedButPaid().isEmpty()) {
            log.warn("支付渠道异常: stalePending={} paidNoLedger={} closedButPaid={}",
                    vo.stalePending().size(), vo.paidNoLedger().size(), vo.closedButPaid().size());
        }
        return vo;
    }

    /** 每日 03:20 全量对账（cron 可配，6 段秒分時日月周）。 */
    @Scheduled(cron = "${billing.reconcile.cron:0 20 3 * * *}")
    public void reconcileDaily() {
        reconcile();
    }

    /**
     * 全量对账：返回差异行（空=全平）。每行差异写审计 + ERROR。
     *
     * @return 差异行列表
     */
    public List<ReconcileDiffVO> reconcile() {
        List<ReconcileDiffVO> diffs = ledgerMapper.findBalanceDiffs();
        if (diffs.isEmpty()) {
            log.info("计费对账全平: 余额=Σ流水 无差异");
            return diffs;
        }
        for (ReconcileDiffVO d : diffs) {
            auditDiff(d);
            log.error("计费对账不平: userId={} balance={} ledgerSum={} diff={}",
                    d.getUserId(), d.getBalancePoints(), d.getLedgerSum(), d.getDiffPoints());
        }
        return diffs;
    }

    /**
     * 组池划拨对账（D4 · 20x-3，Q9=A）：只读，返回 总体平/不平 + 仅异常组明细。
     * <p>恒等式：期望余额 = Σ(ALLOCATE)+Σ(RECLAIM)+Σ(REFUND)+Σ(CONSUME)（白名单四类资金腿，
     * 排除 MEMBER_*、BACKSTOP、ADMIN_ADJUST——见 {@link com.superprogrammer.billing.mapper.GroupReconcileMapper}）。
     * 双账本交叉：组账本划入净额 vs points_ledger GROUP 腿净流出，crossDiff≠0=两账本对不上。
     * <p>异常组逐行写安全审计（billing/group_reconcile_diff）+ ERROR 日志（与个人对账同路径）；
     * 绝不自动修账（修账须人工，防对账程序成为新的篡改面）。
     */
    public com.superprogrammer.billing.dto.GroupReconcileVO groupReconcile() {
        return groupReconcile(null, false);
    }

    /**
     * 组池对账下钻版（7x-1）：参数语义（plan A4 写死）——
     * <ul>
     * <li>groupId 选中 → groups=[该组行(含平)]，totals=该组聚合（单组优先，includeAll 无效）</li>
     * <li>includeAll=true 且无 groupId → groups=全组行(含平组)，totals=全平台</li>
     * <li>都不传 → Q9=A 现状：groups=null，abnormalGroups=仅异常组，totals=全平台</li>
     * </ul>
     * balanced/totals 跟随本响应口径（非恒全平台）；异常行审计/ERROR 与默认口径同路径。
     */
    public com.superprogrammer.billing.dto.GroupReconcileVO groupReconcile(Long groupId, boolean includeAll) {
        List<com.superprogrammer.billing.dto.GroupReconcileRawVO> raws = groupReconcileMapper.selectGroupRawRows(groupId);
        boolean scoped = groupId != null || includeAll;
        java.math.BigDecimal netAllocatedSum = java.math.BigDecimal.ZERO;
        java.math.BigDecimal consumedSum = java.math.BigDecimal.ZERO;
        java.math.BigDecimal refundedSum = java.math.BigDecimal.ZERO;
        java.math.BigDecimal balanceSum = java.math.BigDecimal.ZERO;
        java.math.BigDecimal diffSum = java.math.BigDecimal.ZERO;
        java.math.BigDecimal crossDiffSum = java.math.BigDecimal.ZERO;
        java.util.List<com.superprogrammer.billing.dto.GroupReconcileRowVO> abnormal = new java.util.ArrayList<>();
        java.util.List<com.superprogrammer.billing.dto.GroupReconcileRowVO> scopedRows = new java.util.ArrayList<>();
        for (com.superprogrammer.billing.dto.GroupReconcileRawVO raw : raws) {
            // 派生（写入侧约定：reclaimSum/consumeSum ≤ 0）
            java.math.BigDecimal netAllocated = nvl(raw.allocSum()).add(nvl(raw.reclaimSum()));
            java.math.BigDecimal consumed = nvl(raw.consumeSum()).negate();
            java.math.BigDecimal refunded = nvl(raw.refundSum());
            java.math.BigDecimal expected = netAllocated.add(refunded).subtract(consumed);
            java.math.BigDecimal balance = nvl(raw.balance());
            java.math.BigDecimal diff = balance.subtract(expected);
            java.math.BigDecimal crossDiff = netAllocated.subtract(nvl(raw.personalNetOut()));
            netAllocatedSum = netAllocatedSum.add(netAllocated);
            consumedSum = consumedSum.add(consumed);
            refundedSum = refundedSum.add(refunded);
            balanceSum = balanceSum.add(balance);
            diffSum = diffSum.add(diff);
            crossDiffSum = crossDiffSum.add(crossDiff);
            com.superprogrammer.billing.dto.GroupReconcileRowVO row =
                    new com.superprogrammer.billing.dto.GroupReconcileRowVO(
                            raw.groupId(), raw.groupName(), netAllocated, consumed, refunded,
                            expected, balance, diff, crossDiff);
            if (scoped) {
                scopedRows.add(row);
            }
            if (diff.signum() != 0 || crossDiff.signum() != 0) {
                abnormal.add(row);
                auditGroupDiff(row);
                log.error("组池对账不平: groupId={} name={} netAllocated={} consumed={} refunded={} "
                                + "expected={} balance={} diff={} crossDiff={}",
                        raw.groupId(), raw.groupName(), netAllocated, consumed, refunded,
                        expected, balance, diff, crossDiff);
            }
        }
        if (abnormal.isEmpty()) {
            log.info("组池对账全平: 口径={} 组数={} 余额合计={}", scoped ? "scoped(gid=" + groupId + ",all=" + includeAll + ")" : "default",
                    raws.size(), balanceSum);
        } else {
            log.warn("组池对账异常组数={}/{}", abnormal.size(), raws.size());
        }
        return new com.superprogrammer.billing.dto.GroupReconcileVO(
                abnormal.isEmpty(),
                new com.superprogrammer.billing.dto.GroupReconcileVO.Totals(
                        netAllocatedSum, consumedSum, refundedSum, balanceSum, diffSum, crossDiffSum),
                java.util.List.copyOf(abnormal),
                scoped ? java.util.List.copyOf(scopedRows) : null);
    }

    private static java.math.BigDecimal nvl(java.math.BigDecimal v) {
        return v != null ? v : java.math.BigDecimal.ZERO;
    }

    /** 组池差异行写安全审计（异常吞掉不阻断其余行）。 */
    private void auditGroupDiff(com.superprogrammer.billing.dto.GroupReconcileRowVO r) {
        try {
            String detail = "{\"netAllocated\":" + r.netAllocated()
                    + ",\"consumed\":" + r.consumed()
                    + ",\"refunded\":" + r.refunded()
                    + ",\"expected\":" + r.expected()
                    + ",\"balance\":" + r.balance()
                    + ",\"diff\":" + r.diff()
                    + ",\"crossDiff\":" + r.crossDiff() + "}";
            AuditLogEntity row = auditLogService.fromMdc("billing", "group_reconcile_diff",
                    "project_group", String.valueOf(r.groupId()), detail, "FAIL");
            auditLogService.record(row);
        } catch (Exception e) {
            log.warn("组池对账审计落库失败(已吞): groupId={} {}", r.groupId(), e.toString());
        }
    }

    /** 差异行写安全审计（异步，异常吞掉不阻断其余行）。 */
    private void auditDiff(ReconcileDiffVO d) {
        try {
            String detail = "{\"balance\":" + d.getBalancePoints()
                    + ",\"ledgerSum\":" + d.getLedgerSum()
                    + ",\"diff\":" + d.getDiffPoints() + "}";
            AuditLogEntity row = auditLogService.fromMdc("billing", "reconcile_diff",
                    "wallet", String.valueOf(d.getUserId()), detail, "FAIL");
            row.setUserId(d.getUserId());
            auditLogService.record(row);
        } catch (Exception e) {
            log.warn("对账差异审计落库失败(已吞): userId={} {}", d.getUserId(), e.toString());
        }
    }
}
