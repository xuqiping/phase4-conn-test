package com.superprogrammer.billing.service.channel;

import com.superprogrammer.billing.entity.PaymentOrderEntity;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * MOCK 支付通道（7x#3）：全链路可测的假渠道——走真实回调验签/状态机/入账链路，但不接真钱。
 *
 * <p><b>泄露出生产防线</b>：仅 {@code billing.payment.mock-enabled=true} 时 available；
 * prod profile 开启则 PaymentConfigGuard 启动即炸；可用渠道列表不下发未启用渠道。
 *
 * <p>协议（模拟渠道侧，服务端密钥持有）：
 * <pre>
 * precreate → payToken = HMAC(secret, "PRE|{orderId}|{amount}|{expireEpochSec}")hex，收银台凭它付款
 * notify    → 参数 {channelOrderId=MOCK-{orderId}, amount, payerAccount, success, expire, sign}
 *             sign = HMAC(secret, "NTF|{channelOrderId}|{amount}|{success}|{expire}")hex
 *             expire=epoch 秒，过期签名拒收（防重放）
 * </pre>
 */
@Slf4j
@Component
public class MockPaymentChannel implements PaymentChannelService {

    /** mock 签名密钥（仅环境变量；默认值为本地 dev 占位，prod 必须覆盖且 mock 本就不许开）。 */
    @Value("${billing.payment.mock-secret:dev-mock-secret-do-not-use-in-prod}")
    private String secret;

    @Value("${billing.payment.mock-enabled:false}")
    private boolean mockEnabled;

    @Override
    public String channel() {
        return PaymentOrderEntity.CHANNEL_MOCK;
    }

    @Override
    public boolean available() {
        return mockEnabled;
    }

    /** mock 渠道订单号：MOCK-{orderId}（一单一号，确定性便于测试）。 */
    public static String channelOrderIdOf(long orderId) {
        return "MOCK-" + orderId;
    }

    @Override
    public PaymentPrecreateResult precreate(PaymentOrderEntity order, String notifyUrl) {
        long expire = order.getExpireAt() != null
                ? order.getExpireAt().toEpochSecond()
                : OffsetDateTime.now().plusMinutes(30).toEpochSecond();
        String token = hmacHex("PRE|" + order.getId() + "|" + order.getAmountYuan() + "|" + expire);
        return new PaymentPrecreateResult(token, null, channelOrderIdOf(order.getId()));
    }

    @Override
    public PaymentNotifyData verifyAndParse(Map<String, String> params) {
        String channelOrderId = params.get("channelOrderId");
        String amount = params.get("amount");
        String success = params.get("success");
        String expire = params.get("expire");
        String sign = params.get("sign");
        if (channelOrderId == null || amount == null || success == null || expire == null || sign == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "mock 回调缺参数");
        }
        long expireSec;
        try {
            expireSec = Long.parseLong(expire);
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "mock 回调 expire 非法");
        }
        // 防重放：签名内含 expire，过期即拒（渠道重推应在窗口内）
        if (expireSec < OffsetDateTime.now().toEpochSecond()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "mock 回调已过期（重放拒绝）");
        }
        String expect = hmacHex("NTF|" + channelOrderId + "|" + amount + "|" + success + "|" + expire);
        // 常量时间比较防时序侧信道
        if (!MessageDigest.isEqual(expect.getBytes(StandardCharsets.UTF_8), sign.getBytes(StandardCharsets.UTF_8))) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "mock 回调验签失败");
        }
        java.math.BigDecimal amt;
        try {
            amt = new java.math.BigDecimal(amount);
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "mock 回调金额非法");
        }
        return new PaymentNotifyData(channelOrderId, amt, params.get("payerAccount"), Boolean.parseBoolean(success));
    }

    /** 服务端构造已签名回调参数（mock/trigger 端点专用——密钥不出服务端，前端永远拿不到签名）。 */
    public Map<String, String> buildSignedNotify(PaymentOrderEntity order, boolean success, String payerAccount) {
        String channelOrderId = channelOrderIdOf(order.getId());
        String amount = order.getAmountYuan().toPlainString();
        String expire = String.valueOf(OffsetDateTime.now().plusMinutes(5).toEpochSecond());
        String succ = String.valueOf(success);
        String sign = hmacHex("NTF|" + channelOrderId + "|" + amount + "|" + succ + "|" + expire);
        java.util.Map<String, String> params = new java.util.LinkedHashMap<>();
        params.put("channelOrderId", channelOrderId);
        params.put("amount", amount);
        params.put("success", succ);
        params.put("expire", expire);
        params.put("payerAccount", payerAccount != null ? payerAccount : "mock-user@wallet");
        params.put("sign", sign);
        return params;
    }

    private String hmacHex(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] out = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(out.length * 2);
            for (byte b : out) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("mock HMAC 计算失败", e);
        }
    }
}
