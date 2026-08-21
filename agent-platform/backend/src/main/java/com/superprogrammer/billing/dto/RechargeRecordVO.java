package com.superprogrammer.billing.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 充值记录行（7x#1 六字段口径）：用户账号（行外上下文）/渠道付款账号/金额/积分/充值后余额/时间；
 * 列表顶部累计条由响应包装带 Σ金额/Σ积分（仅 PAID）。
 *
 * @param balanceAfter 充值后余额（JOIN points_ledger 取；PENDING/FAILED/CLOSED 无流水=null 显「—」）
 */
public record RechargeRecordVO(Long id,
                               OffsetDateTime createdAt,
                               String channel,
                               String payerAccount,
                               BigDecimal amountYuan,
                               BigDecimal pointsGranted,
                               BigDecimal balanceAfter,
                               String status) {
}
