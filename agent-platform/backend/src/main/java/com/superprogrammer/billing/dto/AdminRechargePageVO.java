package com.superprogrammer.billing.dto;

import com.superprogrammer.common.result.PageResult;

import java.math.BigDecimal;

/** admin 充值记录分页 + 当前筛选下 Σ（仅 PAID 计入）。 */
public record AdminRechargePageVO(PageResult<AdminRechargeRecordVO> page,
                                  BigDecimal filteredPaidAmount,
                                  BigDecimal filteredPaidPoints) {
}
