package com.superprogrammer.billing.service.channel;

import com.superprogrammer.billing.entity.PaymentOrderEntity;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 支付宝渠道骨架（7x#3 拍板：架构+接口先落，真实商户后接）。
 *
 * <p>真实接入步骤（商户号到位后）：
 * <ol>
 *   <li>配置：billing.payment.alipay.app-id / private-key / alipay-public-key（全环境变量）；</li>
 *   <li>precreate：官方 SDK 当面付/电脑网站支付预下单，payToken=二维码串/表单 html，超时+熔断；</li>
 *   <li>verifyAndParse：RSA2 验签（支付宝公钥）→ 取 out_trade_no/total_amount/buyer_logon_id；
 *       验签含 app_id+notify 时间戳防重放；</li>
 *   <li>available() 改读「三配置齐 + 显式 enable」。</li>
 * </ol>
 * 未配置时一切调用抛「支付宝支付未开通」。
 */
@Component
public class AlipayPaymentChannel implements PaymentChannelService {

    @Value("${billing.payment.alipay.app-id:}")
    private String appId;

    @Override
    public String channel() {
        return PaymentOrderEntity.CHANNEL_ALIPAY;
    }

    @Override
    public boolean available() {
        // 骨架期：仅有 app-id 不算可用（缺密钥对，避免半配置暴露给用户）
        return false;
    }

    @Override
    public PaymentPrecreateResult precreate(PaymentOrderEntity order, String notifyUrl) {
        throw new BusinessException(ErrorCode.BAD_REQUEST, "支付宝支付未开通（商户接入中）");
    }

    @Override
    public PaymentNotifyData verifyAndParse(Map<String, String> params) {
        throw new BusinessException(ErrorCode.BAD_REQUEST, "支付宝支付未开通（商户接入中）");
    }
}
