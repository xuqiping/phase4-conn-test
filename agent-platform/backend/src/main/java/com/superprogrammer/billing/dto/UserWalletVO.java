package com.superprogrammer.billing.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 用户钱包页（{@code GET /api/billing/me/wallet}）。
 * <p>显当前余额 + 最近流水。仅积分维度（不返 token/¥）。
 */
@Data
public class UserWalletVO {
    private BigDecimal balance;
    /** B5（Q10=A）：未偿还欠款（>0 时消费全拦，充值自动冲抵）；无欠款 0。 */
    private BigDecimal debtPoints;
    private List<LedgerItemVO> recentLedger;
}
