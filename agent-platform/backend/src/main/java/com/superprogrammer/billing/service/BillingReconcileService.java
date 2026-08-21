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
