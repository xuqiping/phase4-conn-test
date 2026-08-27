package com.superprogrammer.billing.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** 用户余额视图行（20x#1）：无钱包行/无充值用户显 0（LEFT JOIN COALESCE）。
 *  D2（20x-1）：+name（昵称/姓名，users.name；可空，前端回退 username）。
 *  修复IV E2（12x-1）：+remark（组织备注，可空）。 */
public record UserBalanceRowVO(Long userId,
                               String username,
                               String name,
                               String remark,
                               BigDecimal balancePoints,
                               BigDecimal totalRechargePoints,
                               BigDecimal totalRechargeAmount,
                               OffsetDateTime lastRechargeAt) {
}
