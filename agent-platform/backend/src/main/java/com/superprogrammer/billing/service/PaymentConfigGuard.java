package com.superprogrammer.billing.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 支付配置守卫（7x#3 坑表「mock 泄露出生产」）：prod profile 且 mock 通道开启 → 启动直接失败。
 * 宁可起不来，不可任何人免费充值。
 */
@Slf4j
@Component
public class PaymentConfigGuard {

    @Value("${billing.payment.mock-enabled:false}")
    private boolean mockEnabled;

    @Value("${spring.profiles.active:}")
    private String activeProfiles;

    @PostConstruct
    void guard() {
        boolean prod = java.util.Arrays.stream(activeProfiles.split(","))
                .map(String::trim).anyMatch("prod"::equalsIgnoreCase);
        if (prod && mockEnabled) {
            throw new IllegalStateException(
                    "致命配置：prod 环境不得开启 mock 支付通道（billing.payment.mock-enabled=true）——任何人可免费充值，启动中止");
        }
        if (mockEnabled) {
            log.warn("MOCK 支付通道已开启（非 prod）：充值走假渠道全链路，不产生真实扣款");
        }
    }
}
