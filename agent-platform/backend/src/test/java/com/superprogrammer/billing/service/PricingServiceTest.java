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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PricingService 询价单测：覆盖 5 种 kind 口径 + 无价表降级 + 7x-3 has_reference fallback。
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
        when(pricingRuleMapper.findEffectiveWithResolution("CHAT", 1L, "gpt", false, null)).thenReturn(r);

        BigDecimal cost = pricingService.computeCost("CHAT", 1L, "gpt",
                1_000_000, 1_000_000, null, null, false);

        // 1M×1 + 1M×2 = 3.00
        assertThat(cost).isEqualByComparingTo("3.00");
    }

    @Test
    void embed_cost_ignores_output() {
        PricingRuleEntity r = rule("EMBED");
        r.setPriceInputPerMillion(new BigDecimal("0.50"));
        when(pricingRuleMapper.findEffectiveWithResolution("EMBED", null, "embed-model", false, null)).thenReturn(r);

        BigDecimal cost = pricingService.computeCost("EMBED", null, "embed-model",
                2_000_000, 999_999, null, null, false);

        // 2M×0.5 = 1.00；output 被忽略
        assertThat(cost).isEqualByComparingTo("1.000000");
    }

    @Test
    void rerank_cost_uses_input_tokens_only() {
        PricingRuleEntity r = rule("RERANK");
        r.setPriceInputPerMillion(new BigDecimal("0.80"));
        when(pricingRuleMapper.findEffectiveWithResolution("RERANK", 9L, "rerank-model", false, null)).thenReturn(r);

        BigDecimal cost = pricingService.computeCost("RERANK", 9L, "rerank-model",
                2_000_000, 999_999, null, null, false);

        assertThat(cost).isEqualByComparingTo("1.600000");
    }

    @Test
    void video_token_mode_uses_tokens_times_input_rate() {
        PricingRuleEntity r = rule("VIDEO");
        r.setPriceInputPerMillion(new BigDecimal("3.00"));
        when(pricingRuleMapper.findEffectiveWithResolution("VIDEO", 7L, "seedance", false, null)).thenReturn(r);

        BigDecimal cost = pricingService.computeCost("VIDEO", 7L, "seedance",
                1_000_000, null, 10, null, false);

        // TOKEN 模式（默认）：1M token × 3.0 = 3.00（不看秒数）
        assertThat(cost).isEqualByComparingTo("3.000000");
    }

    @Test
    void video_second_mode_uses_seconds() {
        PricingRuleEntity r = rule("VIDEO");
        r.setVideoBillingMode("SECOND");
        r.setPricePerSecond(new BigDecimal("0.50"));
        when(pricingRuleMapper.findEffectiveWithResolution("VIDEO", 7L, "seedance", false, null)).thenReturn(r);

        BigDecimal cost = pricingService.computeCost("VIDEO", 7L, "seedance",
                999, null, 10, null, false);

        // 10 秒 × 0.5 = 5.00（token 被忽略）
        assertThat(cost).isEqualByComparingTo("5.00");
    }

    @Test
    void image_cost_count_times_unit_price() {
        PricingRuleEntity r = rule("IMAGE");
        r.setPricePerImage(new BigDecimal("0.10"));
        when(pricingRuleMapper.findEffectiveWithResolution("IMAGE", null, "dalle", false, null)).thenReturn(r);

        BigDecimal cost = pricingService.computeCost("IMAGE", null, "dalle",
                null, null, null, 4, false);

        // 4 张 × 0.1 = 0.4
        assertThat(cost).isEqualByComparingTo("0.40");
    }

    @Test
    void no_rule_throws_pricing_not_found() {
        when(pricingRuleMapper.findEffectiveWithResolution("CHAT", null, "ghost", false, null)).thenReturn(null);

        assertThatThrownBy(() -> pricingService.computeCost("CHAT", null, "ghost",
                100, 100, null, null, false))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo(ErrorCode.PRICING_NOT_FOUND.getCode()));
    }

    @Test
    void zero_tokens_yield_zero_cost() {
        PricingRuleEntity r = rule("CHAT");
        r.setPriceInputPerMillion(new BigDecimal("1.00"));
        when(pricingRuleMapper.findEffectiveWithResolution("CHAT", null, "gpt", false, null)).thenReturn(r);

        BigDecimal cost = pricingService.computeCost("CHAT", null, "gpt",
                0, 0, null, null, false);

        assertThat(cost).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ---------------- 7x-3：has_reference fallback ----------------

    @Test
    void video_withReference_hits_true_row() {
        // seeddance 配了 true=10元/百万 → 带参考视频任务命中
        PricingRuleEntity refRow = rule("VIDEO");
        refRow.setPriceInputPerMillion(new BigDecimal("10"));
        when(pricingRuleMapper.findEffectiveWithResolution("VIDEO", 7L, "seedance", true, null)).thenReturn(refRow);

        BigDecimal cost = pricingService.computeCost("VIDEO", 7L, "seedance",
                1_000_000, null, null, null, true);

        // 带参考：1M × 10 = 10
        assertThat(cost).isEqualByComparingTo("10.000000");
        // 不应回退查 false 行
        verify(pricingRuleMapper, org.mockito.Mockito.never())
                .findEffectiveWithResolution(eq("VIDEO"), eq(7L), eq("seedance"), eq(false), isNull());
    }

    @Test
    void video_withReference_fallsBackToFalseRow() {
        // 只配了 false=20元，没配 true → 带参考任务 fallback 到 false 行
        PricingRuleEntity noRefRow = rule("VIDEO");
        noRefRow.setPriceInputPerMillion(new BigDecimal("20"));
        when(pricingRuleMapper.findEffectiveWithResolution("VIDEO", 7L, "seedance", true, null)).thenReturn(null);
        when(pricingRuleMapper.findEffectiveWithResolution("VIDEO", 7L, "seedance", false, null)).thenReturn(noRefRow);

        BigDecimal cost = pricingService.computeCost("VIDEO", 7L, "seedance",
                1_000_000, null, null, null, true);

        // fallback 到无参考价：1M × 20 = 20
        assertThat(cost).isEqualByComparingTo("20.000000");
    }

    @Test
    void video_withReference_noRuleAtAll_throws() {
        // true 和 false 行都没配 → 抛 PRICING_NOT_FOUND（fallback 不无限兜底）
        when(pricingRuleMapper.findEffectiveWithResolution("VIDEO", 7L, "ghost", true, null)).thenReturn(null);
        when(pricingRuleMapper.findEffectiveWithResolution("VIDEO", 7L, "ghost", false, null)).thenReturn(null);

        assertThatThrownBy(() -> pricingService.computeCost("VIDEO", 7L, "ghost",
                1_000_000, null, null, null, true))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("hasReference=true");
    }

    // ---------------- 7x-1（V152）：VIDEO SECOND 分辨率价 ----------------

    @Test
    void videoSecond_exactResolutionRow_used() {
        // 1080p 任务命中 1080p 分辨率行（0.2/秒 × 5s = 1.0），不吃通用行价
        PricingRuleEntity row = rule("VIDEO");
        row.setVideoBillingMode(PricingRuleEntity.VIDEO_MODE_SECOND);
        row.setPricePerSecond(new BigDecimal("0.2"));
        when(pricingRuleMapper.findEffectiveWithResolution("VIDEO", 7L, "seedance", false, "1080p"))
                .thenReturn(row);

        BigDecimal cost = pricingService.computeCost("VIDEO", 7L, "seedance",
                null, null, 5, 0, false, "1080p");

        assertThat(cost).isEqualByComparingTo("1.000000");
    }

    @Test
    void videoSecond_unlistedResolution_fallsBackToGeneralRow() {
        // 480p 未单列 → 通用（resolution=NULL）行兜底（0.1/秒 × 5s = 0.5）
        PricingRuleEntity general = rule("VIDEO");
        general.setVideoBillingMode(PricingRuleEntity.VIDEO_MODE_SECOND);
        general.setPricePerSecond(new BigDecimal("0.1"));
        when(pricingRuleMapper.findEffectiveWithResolution("VIDEO", 7L, "seedance", false, "480p"))
                .thenReturn(null);
        when(pricingRuleMapper.findEffectiveWithResolution("VIDEO", 7L, "seedance", false, null))
                .thenReturn(general);

        BigDecimal cost = pricingService.computeCost("VIDEO", 7L, "seedance",
                null, null, 5, 0, false, "480p");

        assertThat(cost).isEqualByComparingTo("0.500000");
    }

    // ---------------- 7x-2（V152）：estimateVideoYuan 提交期估价 ----------------

    @Test
    void estimateVideoYuan_tokenMode_picksResolutionEst() {
        // 7x-2（V153）：TOKEN 预估按任务分辨率取值——1080p 任务用 1080p 档（0.3×5=1.5）
        PricingRuleEntity row = rule("VIDEO");
        row.setVideoBillingMode(PricingRuleEntity.VIDEO_MODE_TOKEN);
        row.setEstPerResolution("{\"general\":0.1,\"720p\":0.2,\"1080p\":0.3}");
        // TOKEN 行 resolution 恒 NULL：精确(1080p) 查不到 → 回落通用(NULL)行命中（同真实链路）
        when(pricingRuleMapper.findEffectiveWithResolution("VIDEO", 7L, "seedance", false, "1080p"))
                .thenReturn(null);
        when(pricingRuleMapper.findEffectiveWithResolution("VIDEO", 7L, "seedance", false, null))
                .thenReturn(row);

        assertThat(pricingService.estimateVideoYuan(7L, "seedance", 5, "1080p", false))
                .isEqualByComparingTo("1.500000");
    }

    @Test
    void estimateVideoYuan_tokenMode_unlistedResolution_fallsBackToGeneral() {
        // 480p 未单列 → 「通用」档兜底（0.1×5=0.5）
        PricingRuleEntity row = rule("VIDEO");
        row.setVideoBillingMode(PricingRuleEntity.VIDEO_MODE_TOKEN);
        row.setEstPerResolution("{\"general\":0.1,\"720p\":0.2}");
        when(pricingRuleMapper.findEffectiveWithResolution("VIDEO", 7L, "seedance", false, "480p"))
                .thenReturn(null);
        when(pricingRuleMapper.findEffectiveWithResolution("VIDEO", 7L, "seedance", false, null))
                .thenReturn(row);

        assertThat(pricingService.estimateVideoYuan(7L, "seedance", 5, "480p", false))
                .isEqualByComparingTo("0.500000");
    }

    @Test
    void estimateVideoYuan_secondMode_usesResolutionPrice() {
        // SECOND 估价 = 分辨率秒价×时长（与真实扣费同命中链）
        PricingRuleEntity row = rule("VIDEO");
        row.setVideoBillingMode(PricingRuleEntity.VIDEO_MODE_SECOND);
        row.setPricePerSecond(new BigDecimal("0.1"));
        when(pricingRuleMapper.findEffectiveWithResolution("VIDEO", 7L, "seedance", false, "720p"))
                .thenReturn(row);

        assertThat(pricingService.estimateVideoYuan(7L, "seedance", 5, "720p", false))
                .isEqualByComparingTo("0.500000");
    }

    @Test
    void estimateVideoYuan_tokenWithoutEst_throwsPricingNotFound() {
        // 2026-08-25 fail-closed：TOKEN 未配预估秒价 → 抛 PRICING_NOT_FOUND（不再静默记 0，
        // 估价 0 会跳过提交预检与预扣，余额不足用户可白嫖真实生成）
        PricingRuleEntity row = rule("VIDEO");
        row.setVideoBillingMode(PricingRuleEntity.VIDEO_MODE_TOKEN);
        when(pricingRuleMapper.findEffectiveWithResolution("VIDEO", 7L, "seedance", false, null))
                .thenReturn(row);

        assertThatThrownBy(() -> pricingService.estimateVideoYuan(7L, "seedance", 5, null, false))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("est_per_resolution");
    }

    @Test
    void estimateVideoYuan_secondWithoutPrice_throwsPricingNotFound() {
        // 2026-08-25 fail-closed：SECOND 未配秒价 → 抛 PRICING_NOT_FOUND（同上口径）
        PricingRuleEntity row = rule("VIDEO");
        row.setVideoBillingMode(PricingRuleEntity.VIDEO_MODE_SECOND);
        when(pricingRuleMapper.findEffectiveWithResolution("VIDEO", 7L, "seedance", false, null))
                .thenReturn(row);

        assertThatThrownBy(() -> pricingService.estimateVideoYuan(7L, "seedance", 5, null, false))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("price_per_second");
    }

    @Test
    void estimateVideoYuan_noRule_throwsPricingNotFound() {
        when(pricingRuleMapper.findEffectiveWithResolution("VIDEO", 7L, "ghost", false, null))
                .thenReturn(null);
        assertThatThrownBy(() -> pricingService.estimateVideoYuan(7L, "ghost", 5, null, false))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("未配置价表");
    }

    @Test
    void nonVideo_kind_ignores_hasReference_flag() {
        // CHAT 带 hasReference=true：仍按 false 查（effectiveHasRef 对非 VIDEO 恒 false）
        PricingRuleEntity r = rule("CHAT");
        r.setPriceInputPerMillion(new BigDecimal("1"));
        when(pricingRuleMapper.findEffectiveWithResolution("CHAT", 1L, "gpt", false, null)).thenReturn(r);

        BigDecimal cost = pricingService.computeCost("CHAT", 1L, "gpt",
                1_000_000, 0, null, null, true);

        // 忽略 hasReference，按 false 查到 → 1M × 1 = 1
        assertThat(cost).isEqualByComparingTo("1.000000");
    }
}
