package com.superprogrammer.billing.dto;

import com.superprogrammer.common.result.PageResult;

import java.math.BigDecimal;

/**
 * 充值记录分页 + 累计汇总条（7x#1：Σ金额/Σ积分 仅统计 PAID；FAILED/CLOSED 不计）。
 */
public record RechargePageVO(PageResult<RechargeRecordVO> page,
                             BigDecimal totalPaidAmount,
                             BigDecimal totalPaidPoints) {
}
