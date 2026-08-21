package com.superprogrammer.billing.service.channel;

/**
 * 支付渠道回调数据（验签+解析后的统一结构）。
 *
 * @param channelOrderId 渠道订单号（幂等第一道 uk_payment_channel_order 的键）
 * @param amountYuan     渠道侧实付金额（入账前必须与订单金额复核相等）
 * @param payerAccount   渠道付款账号（充值记录六字段；落库，日志掩码）
 * @param success        true=支付成功回调；false=支付失败/关单回调（PENDING→FAILED）
 */
public record PaymentNotifyData(String channelOrderId,
                                java.math.BigDecimal amountYuan,
                                String payerAccount,
                                boolean success) {
}
