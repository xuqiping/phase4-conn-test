package com.superprogrammer.billing.service;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 支付配置守卫（坑表「mock 泄露出生产」）：prod+mock 启动即炸；dev+mock 仅 WARN。 */
class PaymentConfigGuardTest {

    @Test
    void prod开mock_启动炸() {
        PaymentConfigGuard guard = new PaymentConfigGuard();
        ReflectionTestUtils.setField(guard, "mockEnabled", true);
        ReflectionTestUtils.setField(guard, "activeProfiles", "prod");
        assertThatThrownBy(guard::guard)
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("mock");
    }

    @Test
    void prod关mock_放行() {
        PaymentConfigGuard guard = new PaymentConfigGuard();
        ReflectionTestUtils.setField(guard, "mockEnabled", false);
        ReflectionTestUtils.setField(guard, "activeProfiles", "prod");
        assertThatCode(guard::guard).doesNotThrowAnyException();
    }

    @Test
    void dev开mock_放行() {
        PaymentConfigGuard guard = new PaymentConfigGuard();
        ReflectionTestUtils.setField(guard, "mockEnabled", true);
        ReflectionTestUtils.setField(guard, "activeProfiles", "dev");
        assertThatCode(guard::guard).doesNotThrowAnyException();
    }

    @Test
    void 多profile含prod_仍炸() {
        PaymentConfigGuard guard = new PaymentConfigGuard();
        ReflectionTestUtils.setField(guard, "mockEnabled", true);
        ReflectionTestUtils.setField(guard, "activeProfiles", "prod,oss");
        assertThatThrownBy(guard::guard).isInstanceOf(IllegalStateException.class);
    }
}
