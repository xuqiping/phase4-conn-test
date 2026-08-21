package com.superprogrammer.billing.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** admin 充值记录行（7x#1 六字段 + 用户账号列）。 */
public record AdminRechargeRecordVO(Long id,
                                    Long userId,
                                    String username,
                                    OffsetDateTime createdAt,
                                    String channel,
                                    String payerAccount,
                                    BigDecimal amountYuan,
                                    BigDecimal pointsGranted,
                                    BigDecimal balanceAfter,
                                    String status) {
}
