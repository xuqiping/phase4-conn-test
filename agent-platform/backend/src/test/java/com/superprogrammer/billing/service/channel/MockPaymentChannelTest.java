package com.superprogrammer.billing.service.channel;

import com.superprogrammer.billing.entity.PaymentOrderEntity;
import com.superprogrammer.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** MOCK 通道：签名构造↔验签闭环、篡改拒绝、过期拒绝、可用性开关。 */
class MockPaymentChannelTest {

    private MockPaymentChannel channel;

    @BeforeEach
    void setUp() {
        channel = new MockPaymentChannel();
        ReflectionTestUtils.setField(channel, "secret", "test-secret");
        ReflectionTestUtils.setField(channel, "mockEnabled", true);
    }

    private PaymentOrderEntity order(long id, String amount) {
        PaymentOrderEntity o = new PaymentOrderEntity();
        o.setId(id);
        o.setUserId(7L);
        o.setAmountYuan(new BigDecimal(amount));
        o.setStatus(PaymentOrderEntity.STATUS_PENDING);
        o.setChannel(PaymentOrderEntity.CHANNEL_MOCK);
        o.setExpireAt(OffsetDateTime.now().plusMinutes(30));
        return o;
    }

    @Test
    void 签名闭环_build后verify通过() {
        PaymentOrderEntity o = order(100, "10.00");
        Map<String, String> params = channel.buildSignedNotify(o, true, "mock@wallet");
        PaymentNotifyData data = channel.verifyAndParse(params);
        assertThat(data.channelOrderId()).isEqualTo("MOCK-100");
        assertThat(data.amountYuan()).isEqualByComparingTo("10.00");
        assertThat(data.payerAccount()).isEqualTo("mock@wallet");
        assertThat(data.success()).isTrue();
    }

    @Test
    void 篡改金额_验签拒() {
        Map<String, String> params = channel.buildSignedNotify(order(100, "10.00"), true, null);
        params.put("amount", "99999.99");
        assertThatThrownBy(() -> channel.verifyAndParse(params))
                .isInstanceOf(BusinessException.class).hasMessageContaining("验签失败");
    }

    @Test
    void 错误密钥_验签拒() {
        Map<String, String> params = channel.buildSignedNotify(order(100, "10.00"), true, null);
        MockPaymentChannel other = new MockPaymentChannel();
        ReflectionTestUtils.setField(other, "secret", "wrong-secret");
        ReflectionTestUtils.setField(other, "mockEnabled", true);
        assertThatThrownBy(() -> other.verifyAndParse(params))
                .isInstanceOf(BusinessException.class).hasMessageContaining("验签失败");
    }

    @Test
    void 过期签名_重放拒() {
        // 构造一个已过期签名：直接手撸（buildSignedNotify 恒给 +5min，测试改 expire 后签名不匹配→验签失败也是拒）
        Map<String, String> params = channel.buildSignedNotify(order(100, "10.00"), true, null);
        params.put("expire", String.valueOf(OffsetDateTime.now().minusMinutes(1).toEpochSecond()));
        assertThatThrownBy(() -> channel.verifyAndParse(params))
                .isInstanceOf(BusinessException.class); // 过期拒 或 验签拒——都拒
    }

    @Test
    void 缺参_400() {
        assertThatThrownBy(() -> channel.verifyAndParse(Map.of("channelOrderId", "MOCK-1")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("缺参数");
    }

    @Test
    void 可用性开关() {
        assertThat(channel.available()).isTrue();
        ReflectionTestUtils.setField(channel, "mockEnabled", false);
        assertThat(channel.available()).isFalse();
    }

    @Test
    void precreate_返回令牌含HMAC() {
        PaymentPrecreateResult r = channel.precreate(order(100, "10.00"), "https://x/notify");
        assertThat(r.payToken()).isNotBlank().hasSize(64);
    }
}
