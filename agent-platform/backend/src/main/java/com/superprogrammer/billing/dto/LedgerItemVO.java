package com.superprogrammer.billing.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 用户钱包流水行（{@code GET /api/billing/me/wallet}）。
 * <p>用户侧仅显积分维度：delta（正=充值/退款，负=扣减）+ 类型 + 备注 + 时间。
 * <b>不含</b> moneyYuan/costYuan/token（用户侧不暴露 ¥ 与 token，spec §3）。
 */
@Data
public class LedgerItemVO {
    private OffsetDateTime createdAt;
    /** CONSUME/REFUND/ADMIN_GRANT/RECHARGE。 */
    private String type;
    /** 正=入账（充值/退款），负=扣减。 */
    private BigDecimal deltaPoints;
    private BigDecimal balanceAfter;
    private String remark;
}
