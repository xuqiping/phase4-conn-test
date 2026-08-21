package com.superprogrammer.billing.service.channel;

/**
 * 渠道下单（precreate）结果。
 *
 * @param payToken  支付凭证：mock=收银台令牌；真实渠道=二维码串/表单 html/预支付 id（接入时按渠道语义填）
 * @param payUrl    可选跳转地址（mock 为空）
 */
public record PaymentPrecreateResult(String payToken, String payUrl) {
}
