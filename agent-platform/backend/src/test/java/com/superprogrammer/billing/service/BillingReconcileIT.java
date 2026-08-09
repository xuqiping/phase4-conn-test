package com.superprogrammer.billing.service;

import com.superprogrammer.billing.dto.ReconcileDiffVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 安全体系 S1 · SEC-FR-123 计费对账 IT（真 PG FULL OUTER JOIN）。
 * AC：直改余额（无流水）→ 对账检出差异行；正常一致用户 → 不在差异里。
 */
@SpringBootTest
@Tag("integration")
@ActiveProfiles("it")
class BillingReconcileIT {

    private static final long UID_TAMPERED = 990000011L;  // 余额被直改（无对应流水）
    private static final long UID_NORMAL = 990000012L;    // 正常用户（余额=Σ流水）

    @Autowired
    private BillingReconcileService reconcileService;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        clean();
        // 篡改户：余额 100，流水只记了 60（模拟 UPDATE 余额绕过统一入口）
        jdbc.update("INSERT INTO user_points_balance (user_id, balance_points, updated_at) VALUES (?, 100.00, NOW())",
                UID_TAMPERED);
        jdbc.update("INSERT INTO points_ledger (user_id, type, delta_points, balance_after, created_at) "
                + "VALUES (?, 'ADMIN_GRANT', 60.00, 60.00, NOW())", UID_TAMPERED);
        // 正常户：余额 80 = Σ流水 80
        jdbc.update("INSERT INTO user_points_balance (user_id, balance_points, updated_at) VALUES (?, 80.00, NOW())",
                UID_NORMAL);
        jdbc.update("INSERT INTO points_ledger (user_id, type, delta_points, balance_after, created_at) "
                + "VALUES (?, 'ADMIN_GRANT', 100.00, 100.00, NOW())", UID_NORMAL);
        jdbc.update("INSERT INTO points_ledger (user_id, type, delta_points, balance_after, created_at) "
                + "VALUES (?, 'CONSUME', -20.00, 80.00, NOW())", UID_NORMAL);
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM points_ledger WHERE user_id IN (?, ?)", UID_TAMPERED, UID_NORMAL);
        jdbc.update("DELETE FROM user_points_balance WHERE user_id IN (?, ?)", UID_TAMPERED, UID_NORMAL);
    }

    // AC-SEC-FR-123：手工改余额 → 对账 diff 告警（篡改户被检出，diff=40）
    @Test
    void reconcile_tamperedBalance_detected() {
        List<ReconcileDiffVO> diffs = reconcileService.reconcile();

        ReconcileDiffVO tampered = diffs.stream()
                .filter(d -> UID_TAMPERED == d.getUserId()).findFirst().orElse(null);
        assertThat(tampered).as("篡改户必须被检出").isNotNull();
        assertThat(tampered.getBalancePoints()).isEqualByComparingTo("100.00");
        assertThat(tampered.getLedgerSum()).isEqualByComparingTo("60.00");
        assertThat(tampered.getDiffPoints()).as("余额-流水=40").isEqualByComparingTo("40.00");
    }

    // AC-SEC-FR-123：正常户（余额=Σ流水）不出现在差异里
    @Test
    void reconcile_consistentUser_notFlagged() {
        List<ReconcileDiffVO> diffs = reconcileService.reconcile();

        assertThat(diffs.stream().filter(d -> UID_NORMAL == d.getUserId()).findFirst())
                .as("正常户不应被误报").isEmpty();
    }

    // AC-SEC-FR-123：平账日全对账后，差异写审计（audit_logs 有 reconcile_diff 行）
    @Test
    void reconcile_diff_writesAuditRow() throws Exception {
        reconcileService.reconcile();

        // 审计为异步落库（auditTaskExecutor），轮询等待最长 10s
        Integer rows = 0;
        for (int i = 0; i < 50; i++) {
            rows = jdbc.queryForObject(
                    "SELECT count(*) FROM audit_logs WHERE module='billing' AND action='reconcile_diff' "
                            + "AND target_id = ?", Integer.class, String.valueOf(UID_TAMPERED));
            if (rows != null && rows >= 1) {
                break;
            }
            Thread.sleep(200);
        }
        assertThat(rows).as("差异行写安全审计").isGreaterThanOrEqualTo(1);
        jdbc.update("DELETE FROM audit_logs WHERE module='billing' AND action='reconcile_diff' "
                + "AND target_id = ?", String.valueOf(UID_TAMPERED));
    }

    // 辅助：余额快照-流水恒等式的反向验证（篡改户本不应平）
    @Test
    void reconcile_diffSignSemantics() {
        List<ReconcileDiffVO> diffs = reconcileService.reconcile();
        ReconcileDiffVO tampered = diffs.stream()
                .filter(d -> UID_TAMPERED == d.getUserId()).findFirst().orElseThrow();
        BigDecimal expected = tampered.getBalancePoints().subtract(tampered.getLedgerSum());
        assertThat(tampered.getDiffPoints()).isEqualByComparingTo(expected);
    }
}
