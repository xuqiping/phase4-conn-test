package com.superprogrammer.billing.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 支付渠道异常行（对账「渠道异常」节，7x#3 运维入口）——人工补单线索，只读不自动修。
 */
public record PaymentAnomalyRowVO(Long orderId,
                                  Long userId,
                                  BigDecimal amountYuan,
                                  BigDecimal pointsGranted,
                                  String status,
                                  String channel,
                                  OffsetDateTime createdAt,
                                  OffsetDateTime paidAt) {
}
