package com.superprogrammer.billing.service.channel;

import com.superprogrammer.billing.entity.PaymentOrderEntity;

import java.util.Map;

/**
 * 支付渠道抽象（7x#3）：下单 + 回调验签解析 + 可用性判定。
 *
 * <p>实现：{@link MockPaymentChannel}（全链路可测）/ AlipayPaymentChannel / WechatPaymentChannel（骨架留配置位）。
 * <p><b>安全约定</b>：verifyAndParse 必须先验签再解析，任何验签失败抛异常（由 PaymentOrderService 统一
 * 记安全事件）；实现类不得在日志打印签名原文与完整密钥。
 */
public interface PaymentChannelService {

    /** 渠道码（PaymentOrderEntity.CHANNEL_*）。 */
    String channel();

    /** 配置齐全且启用才 true；false 的渠道不下发给前端、下单拒绝。 */
    boolean available();

    /**
     * 下单（预创建）：返回支付凭证。真实渠道在此调其服务端 SDK（接入时实现，注意超时/熔断）。
     *
     * @param order    已落库的 PENDING 订单（含快照积分/过期时间）
     * @param notifyUrl 我方回调地址（部署须 HTTPS，见部署 checklist）
     */
    PaymentPrecreateResult precreate(PaymentOrderEntity order, String notifyUrl);

    /**
     * 回调验签+解析。验签失败/报文非法抛 {@link com.superprogrammer.common.exception.BusinessException}（BAD_REQUEST）。
     *
     * @param params 回调参数（form 参数或 json 拍平后的 string map，由各渠道 notify 端点适配）
     */
    PaymentNotifyData verifyAndParse(Map<String, String> params);
}
