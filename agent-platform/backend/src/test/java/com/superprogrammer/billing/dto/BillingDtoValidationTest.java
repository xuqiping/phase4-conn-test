package com.superprogrammer.billing.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 安全体系 S1 · SEC-FR-122 金额参数校验：DTO Bean Validation 注解直测。
 * <p>挡：负数 / 零 / 超上限（1 亿）。Controller 层 {@code @Valid} 已在
 * PricingConfigController/WalletAdminController 全量挂上，由 GlobalExceptionHandler 转 400。
 */
class BillingDtoValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    // ---------- RechargeRequest ----------

    @Test
    void recharge_negativePoints_violated() {
        RechargeRequest req = new RechargeRequest();
        req.setUserId(1L);
        req.setPoints(new BigDecimal("-5"));
        assertThat(fields(validator.validate(req))).contains("points");
    }

    @Test
    void recharge_zeroPoints_violated() {
        RechargeRequest req = new RechargeRequest();
        req.setUserId(1L);
        req.setPoints(BigDecimal.ZERO);
        assertThat(fields(validator.validate(req))).contains("points");
    }

    @Test
    void recharge_overCapPoints_violated() {
        RechargeRequest req = new RechargeRequest();
        req.setUserId(1L);
        req.setPoints(new BigDecimal("100000001")); // >1 亿上限
        assertThat(fields(validator.validate(req))).contains("points");
    }

    @Test
    void recharge_normal_passes() {
        RechargeRequest req = new RechargeRequest();
        req.setUserId(1L);
        req.setPoints(new BigDecimal("1000"));
        assertThat(validator.validate(req)).isEmpty();
    }

    // ---------- PricingRuleRequest ----------

    @Test
    void pricing_negativePrice_violated() {
        PricingRuleRequest req = new PricingRuleRequest();
        req.setKind("CHAT");
        req.setModel("gpt-x");
        req.setPriceInputPerMillion(new BigDecimal("-0.01"));
        assertThat(fields(validator.validate(req))).contains("priceInputPerMillion");
    }

    @Test
    void pricing_overCapPrice_violated() {
        PricingRuleRequest req = new PricingRuleRequest();
        req.setKind("CHAT");
        req.setModel("gpt-x");
        req.setPriceOutputPerMillion(new BigDecimal("999999999"));
        assertThat(fields(validator.validate(req))).contains("priceOutputPerMillion");
    }

    @Test
    void pricing_normal_passes() {
        PricingRuleRequest req = new PricingRuleRequest();
        req.setKind("CHAT");
        req.setModel("gpt-x");
        req.setPriceInputPerMillion(new BigDecimal("2.50"));
        req.setPriceOutputPerMillion(new BigDecimal("10.00"));
        assertThat(validator.validate(req)).isEmpty();
    }

    // ---------- RatioTierRequest ----------

    @Test
    void tier_negativeMin_violated() {
        RatioTierRequest req = new RatioTierRequest();
        req.setMinAmount(new BigDecimal("-1"));
        req.setRatio(new BigDecimal("100"));
        assertThat(fields(validator.validate(req))).contains("minAmount");
    }

    @Test
    void tier_zeroRatio_violated() {
        RatioTierRequest req = new RatioTierRequest();
        req.setMinAmount(BigDecimal.ZERO);
        req.setRatio(BigDecimal.ZERO);
        assertThat(fields(validator.validate(req))).contains("ratio");
    }

    @Test
    void tier_nullMaxMeansInfinity_passes() {
        RatioTierRequest req = new RatioTierRequest();
        req.setMinAmount(BigDecimal.ZERO);
        req.setMaxAmount(null); // ∞
        req.setRatio(new BigDecimal("100"));
        assertThat(validator.validate(req)).isEmpty();
    }

    private static Set<String> fields(Set<? extends ConstraintViolation<?>> violations) {
        return violations.stream()
                .map(v -> v.getPropertyPath().toString())
                .collect(java.util.stream.Collectors.toSet());
    }
}
