package com.superprogrammer.billing.service.channel;

import com.superprogrammer.billing.entity.PaymentOrderEntity;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 微信支付渠道骨架（7x#3 拍板：架构+接口先落，真实商户后接）。
 *
 * <p>真实接入步骤（商户号到位后）：
 * <ol>
 *   <li>配置：billing.payment.wechat.mch-id / app-id / api-v3-key / 平台证书序列号（全环境变量）；</li>
 *   <li>precreate：Native 下单（V3 接口，平台证书加密敏感字段），payToken=code_url；</li>
 *   <li>verifyAndParse：V3 回调头 Wechatpay-Signature 验签 + AES-GCM 解密 resource
 *       → out_trade_no/amount.total/payer.openid；nonce+timestamp 防重放；</li>
 *   <li>available() 改读「配置齐 + 显式 enable」。</li>
 * </ol>
 * 未配置时一切调用抛「微信支付未开通」。
 */
@Component
public class WechatPaymentChannel implements PaymentChannelService {

    @Value("${billing.payment.wechat.mch-id:}")
    private String mchId;

    @Override
    public String channel() {
        return PaymentOrderEntity.CHANNEL_WECHAT;
    }

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public PaymentPrecreateResult precreate(PaymentOrderEntity order, String notifyUrl) {
        throw new BusinessException(ErrorCode.BAD_REQUEST, "微信支付未开通（商户接入中）");
    }

    @Override
    public PaymentNotifyData verifyAndParse(Map<String, String> params) {
        throw new BusinessException(ErrorCode.BAD_REQUEST, "微信支付未开通（商户接入中）");
    }
}
