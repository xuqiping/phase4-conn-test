package com.superprogrammer.billing.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** 用户余额视图行（20x#1）：无钱包行/无充值用户显 0（LEFT JOIN COALESCE）。
 *  D2（20x-1）：+name（昵称/姓名，users.name；可空，前端回退 username）。 */
public record UserBalanceRowVO(Long userId,
                               String username,
                               String name,
                               BigDecimal balancePoints,
                               BigDecimal totalRechargePoints,
                               BigDecimal totalRechargeAmount,
                               OffsetDateTime lastRechargeAt) {
}
