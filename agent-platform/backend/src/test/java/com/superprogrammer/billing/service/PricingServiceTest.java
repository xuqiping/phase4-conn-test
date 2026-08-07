package com.superprogrammer.billing.service;

import com.superprogrammer.billing.entity.PricingRuleEntity;
import com.superprogrammer.billing.mapper.PricingRuleMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * PricingService 询价单测：覆盖 5 种 kind 口径 + 无价表降级。
 * 纯 Mockito（@Select 注解 SQL，无 LambdaQueryWrapper，无需 TableInfoHelper.initTableInfo）。
 */
@ExtendWith(MockitoExtension.class)
class PricingServiceTest {

    @Mock
    private PricingRuleMapper pricingRuleMapper;

    @InjectMocks
    private PricingService pricingService;

    private PricingRuleEntity rule(String kind) {
        PricingRuleEntity r = new PricingRuleEntity();
        r.setKind(kind);
        return r;
    }

    @BeforeEach
    void nothing() {
        // 各 case 独立 stub
    }

    @Test
    void chat_cost_sums_input_and_output_per_million() {
        PricingRuleEntity r = rule("CHAT");
        r.setPriceInputPerMillion(new BigDecimal("1.00"));
        r.setPriceOutputPerMillion(new BigDecimal("2.00"));
        when(pricingRuleMapper.findEffective("CHAT", 1L, "gpt")).thenReturn(r);

        BigDecimal cost = pricingService.computeCost("CHAT", 1L, "gpt",
                1_000_000, 1_000_000, null, null);

        // 1M×1 + 1M×2 = 3.00
        assertThat(cost).isEqualByComparingTo("3.00");
    }

    @Test
    void embed_cost_ignores_output() {
        PricingRuleEntity r = rule("EMBED");
        r.setPriceInputPerMillion(new BigDecimal("0.50"));
        when(pricingRuleMapper.findEffective("EMBED", null, "embed-model")).thenReturn(r);

        BigDecimal cost = pricingService.computeCost("EMBED", null, "embed-model",
                2_000_000, 999_999, null, null);

        // 2M×0.5 = 1.00；output 被忽略
        assertThat(cost).isEqualByComparingTo("1.000000");
    }

    @Test
    void video_token_mode_uses_tokens_times_input_rate() {
        PricingRuleEntity r = rule("VIDEO");
        r.setPriceInputPerMillion(new BigDecimal("3.00"));
        when(pricingRuleMapper.findEffective("VIDEO", 7L, "seedance")).thenReturn(r);

        BigDecimal cost = pricingService.computeCost("VIDEO", 7L, "seedance",
                1_000_000, null, 10, null);

        // TOKEN 模式（默认）：1M token × 3.0 = 3.00（不看秒数）
        assertThat(cost).isEqualByComparingTo("3.000000");
    }

    @Test
    void video_second_mode_uses_seconds() {
        PricingRuleEntity r = rule("VIDEO");
        r.setVideoBillingMode("SECOND");
        r.setPricePerSecond(new BigDecimal("0.50"));
        when(pricingRuleMapper.findEffective("VIDEO", 7L, "seedance")).thenReturn(r);

        BigDecimal cost = pricingService.computeCost("VIDEO", 7L, "seedance",
                999, null, 10, null);

        // 10 秒 × 0.5 = 5.00（token 被忽略）
        assertThat(cost).isEqualByComparingTo("5.00");
    }

    @Test
    void image_cost_count_times_unit_price() {
        PricingRuleEntity r = rule("IMAGE");
        r.setPricePerImage(new BigDecimal("0.10"));
        when(pricingRuleMapper.findEffective("IMAGE", null, "dalle")).thenReturn(r);

        BigDecimal cost = pricingService.computeCost("IMAGE", null, "dalle",
                null, null, null, 4);

        // 4 张 × 0.1 = 0.4
        assertThat(cost).isEqualByComparingTo("0.40");
    }

    @Test
    void no_rule_throws_pricing_not_found() {
        when(pricingRuleMapper.findEffective("CHAT", null, "ghost")).thenReturn(null);

        assertThatThrownBy(() -> pricingService.computeCost("CHAT", null, "ghost",
                100, 100, null, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo(ErrorCode.PRICING_NOT_FOUND.getCode()));
    }

    @Test
    void zero_tokens_yield_zero_cost() {
        PricingRuleEntity r = rule("CHAT");
        r.setPriceInputPerMillion(new BigDecimal("1.00"));
        when(pricingRuleMapper.findEffective("CHAT", null, "gpt")).thenReturn(r);

        BigDecimal cost = pricingService.computeCost("CHAT", null, "gpt",
                0, 0, null, null);

        assertThat(cost).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
