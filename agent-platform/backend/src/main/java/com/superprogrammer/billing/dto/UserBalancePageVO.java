package com.superprogrammer.billing.dto;

import com.superprogrammer.common.result.PageResult;

import java.math.BigDecimal;

/**
 * 用户余额视图分页 + 全平台合计卡（20x#1）。
 * 合计与明细同查询口径（同一聚合子查询），保证卡=明细Σ。
 */
public record UserBalancePageVO(PageResult<UserBalanceRowVO> page,
                                long totalUsers,
                                BigDecimal sumBalance,
                                BigDecimal sumRechargePoints,
                                BigDecimal sumRechargeAmount) {
}
