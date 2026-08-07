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
    private List<LedgerItemVO> recentLedger;
}
