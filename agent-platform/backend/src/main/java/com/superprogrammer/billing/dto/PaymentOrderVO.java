package com.superprogrammer.billing.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 支付订单 VO（用户侧查单/下单响应；admin 记录复用）。
 *
 * @param payToken 支付凭证（仅 PENDING 且创建/查单时下发；mock=收银台令牌）
 */
public record PaymentOrderVO(Long id,
                             OffsetDateTime createdAt,
                             BigDecimal amountYuan,
                             BigDecimal pointsGranted,
                             String status,
                             String channel,
                             String payerAccount,
                             OffsetDateTime expireAt,
                             OffsetDateTime paidAt,
                             String payToken) {
}
