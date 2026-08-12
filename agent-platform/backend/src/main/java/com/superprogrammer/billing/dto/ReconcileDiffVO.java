package com.superprogrammer.billing.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 计费对账差异行（安全体系 S1 · SEC-FR-123）。
 * <p>不变量：用户余额 = Σ其全部流水 delta（余额行只经 adjust 路径变动，每变必落流水）。
 * diffPoints ≠ 0 即「流水-余额」不平——疑似绕过 charge 统一入口直改余额 / 流水被篡改。
 */
@Data
public class ReconcileDiffVO {

    private Long userId;

    /** user_points_balance.balance_points（无余额行按 0 计）。 */
    private BigDecimal balancePoints;

    /** Σ points_ledger.delta_points（无流水按 0 计）。 */
    private BigDecimal ledgerSum;

    /** balance - ledgerSum。正=余额多于流水（疑似直改余额），负=流水多于余额。 */
    private BigDecimal diffPoints;
}
