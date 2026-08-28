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

    // ---------------- V162：TOKEN 每百万价分档槽位（token_price_per_resolution） ----------------

    @Test
    void video_token_resolutionSlot_used() {
        // 4K 任务命中 4k 槽（111.2/百万 × 1M = 111.2）；"4K" 大写经 normalizeResolution 归一取键
        PricingRuleEntity row = rule("VIDEO");
        row.setPriceInputPerMillion(new BigDecimal("12"));
        row.setTokenPricePerResolution("{\"480p\":6.5,\"4k\":111.2}");
        when(pricingRuleMapper.findEffectiveWithResolution("VIDEO", 7L, "seedance", false, "4k"))
                .thenReturn(null);
        when(pricingRuleMapper.findEffectiveWithResolution("VIDEO", 7L, "seedance", false, null))
                .thenReturn(row);

        BigDecimal cost = pricingService.computeCost("VIDEO", 7L, "seedance",
                1_000_000, null, 5, 0, false, "4K");

        assertThat(cost).isEqualByComparingTo("111.200000");
    }

    @Test
    void video_token_unlistedSlot_fallsBackToGeneral() {
        // 720p 未配槽 → 回落通用价 12（Q2 决策：未配档=通用价，零迁移兼容）
        PricingRuleEntity row = rule("VIDEO");
        row.setPriceInputPerMillion(new BigDecimal("12"));
        row.setTokenPricePerResolution("{\"480p\":6.5,\"4k\":111.2}");
        when(pricingRuleMapper.findEffectiveWithResolution("VIDEO", 7L, "seedance", false, "720p"))
                .thenReturn(null);
        when(pricingRuleMapper.findEffectiveWithResolution("VIDEO", 7L, "seedance", false, null))
                .thenReturn(row);

        BigDecimal cost = pricingService.computeCost("VIDEO", 7L, "seedance",
                1_000_000, null, 5, 0, false, "720p");

        assertThat(cost).isEqualByComparingTo("12.000000");
    }

    @Test
    void video_token_slotAndGeneralBothMissing_zeroCost() {
        // 行在但槽+通用价全空 → 0 元交付（缺价=0 元现状口径钉死，规格 VTR-2）
        PricingRuleEntity row = rule("VIDEO");
        when(pricingRuleMapper.findEffectiveWithResolution("VIDEO", 7L, "seedance", false, "4k"))
                .thenReturn(null);
        when(pricingRuleMapper.findEffectiveWithResolution("VIDEO", 7L, "seedance", false, null))
                .thenReturn(row);

        BigDecimal cost = pricingService.computeCost("VIDEO", 7L, "seedance",
                1_000_000, null, 5, 0, false, "4k");

        assertThat(cost).isEqualByComparingTo("0");
    }

    @Test
    void video_token_resolutionNull_ignoresSlots() {
        // 分辨率未传（旧链路/无参兜底）→ 不进槽，直取通用价
        PricingRuleEntity row = rule("VIDEO");
        row.setPriceInputPerMillion(new BigDecimal("12"));
        row.setTokenPricePerResolution("{\"4k\":111.2}");
        when(pricingRuleMapper.findEffectiveWithResolution("VIDEO", 7L, "seedance", false, null))
                .thenReturn(row);

        BigDecimal cost = pricingService.computeCost("VIDEO", 7L, "seedance",
                1_000_000, null, null, null, false);

        assertThat(cost).isEqualByComparingTo("12.000000");
    }

    @Test
    void video_token_dirtySlotJson_fallsBackToGeneral() {
        // 脏 JSON → WARN + 回落通用价，不炸结算（fail-open：脏价表不废任务）
        PricingRuleEntity row = rule("VIDEO");
        row.setPriceInputPerMillion(new BigDecimal("12"));
        row.setTokenPricePerResolution("{bad json");
        when(pricingRuleMapper.findEffectiveWithResolution("VIDEO", 7L, "seedance", false, "4k"))
                .thenReturn(null);
        when(pricingRuleMapper.findEffectiveWithResolution("VIDEO", 7L, "seedance", false, null))
                .thenReturn(row);

        BigDecimal cost = pricingService.computeCost("VIDEO", 7L, "seedance",
                1_000_000, null, 5, 0, false, "4k");

        assertThat(cost).isEqualByComparingTo("12.000000");
    }

    @Test
    void video_token_hasRefFallback_usesFallbackRowSlots() {
        // 只配无参考行（带 4k 槽）→ 带参考任务 fallback 命中该行，槽也取该行的（不跨行拼价，VTR-3）
        PricingRuleEntity noRefRow = rule("VIDEO");
        noRefRow.setPriceInputPerMillion(new BigDecimal("20"));
        noRefRow.setTokenPricePerResolution("{\"4k\":222}");
        when(pricingRuleMapper.findEffectiveWithResolution("VIDEO", 7L, "seedance", true, "4k"))
                .thenReturn(null);
        when(pricingRuleMapper.findEffectiveWithResolution("VIDEO", 7L, "seedance", true, null))
                .thenReturn(null);
        when(pricingRuleMapper.findEffectiveWithResolution("VIDEO", 7L, "seedance", false, "4k"))
                .thenReturn(null);
        when(pricingRuleMapper.findEffectiveWithResolution("VIDEO", 7L, "seedance", false, null))
                .thenReturn(noRefRow);

        BigDecimal cost = pricingService.computeCost("VIDEO", 7L, "seedance",
                1_000_000, null, 5, 0, true, "4k");

        assertThat(cost).isEqualByComparingTo("222.000000");
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

    // ==================== 人工测试遗留问题修复II D2（V160）：缓存三腿 + 闲时 ====================

    @Mock
    private com.superprogrammer.system.service.SystemSettingService systemSettingService;

    /** 闲时配置 JSON（22:00-08:00 跨零点；周末全天）。 */
    private static final String OFF_PEAK_JSON =
            "{\"enabled\":true,\"timezone\":\"Asia/Shanghai\","
                    + "\"weekday\":[{\"start\":\"22:00\",\"end\":\"08:00\"}],"
                    + "\"weekend\":[{\"start\":\"00:00\",\"end\":\"24:00\"}]}";

    private PricingRuleEntity chatRule() {
        PricingRuleEntity r = rule("CHAT");
        r.setPriceInputPerMillion(new BigDecimal("1.00"));
        r.setPriceOutputPerMillion(new BigDecimal("2.00"));
        return r;
    }

    @Test
    void chat_cachedThirdLeg_pricedSeparately() {
        PricingRuleEntity r = chatRule();
        r.setPriceCachedPerMillion(new BigDecimal("0.50"));
        when(pricingRuleMapper.findEffectiveWithResolution("CHAT", 1L, "gpt", false, null)).thenReturn(r);

        // 忙时（无配置 → isOffPeak=false）：1M in×1 + 1M cached×0.5 + 0 out = 1.50
        BigDecimal cost = pricingService.computeCost("CHAT", 1L, "gpt",
                1_000_000, 0, null, null, false, null, 1_000_000L);
        assertThat(cost).isEqualByComparingTo("1.50");
    }

    @Test
    void chat_cachedPriceNull_fallsBackInputPrice() {
        PricingRuleEntity r = chatRule();
        when(pricingRuleMapper.findEffectiveWithResolution("CHAT", 1L, "gpt", false, null)).thenReturn(r);

        // priceCached=NULL → 缓存价=输入价：1M in + 1M cached 均 ×1 = 2.00
        BigDecimal cost = pricingService.computeCost("CHAT", 1L, "gpt",
                1_000_000, 0, null, null, false, null, 1_000_000L);
        assertThat(cost).isEqualByComparingTo("2.00");
    }

    @Test
    void chat_cachedNull_degeneratesTwoLeg_sameAsBefore() {
        // 硬门槛：cachedTokens=null → 与老两腿口径逐分一致
        PricingRuleEntity r = chatRule();
        when(pricingRuleMapper.findEffectiveWithResolution("CHAT", 1L, "gpt", false, null)).thenReturn(r);

        BigDecimal legacy = pricingService.computeCost("CHAT", 1L, "gpt",
                1_000_000, 1_000_000, null, null, false);
        BigDecimal withNullCached = pricingService.computeCost("CHAT", 1L, "gpt",
                1_000_000, 1_000_000, null, null, false, null, null);
        assertThat(withNullCached).isEqualByComparingTo(legacy);
        assertThat(withNullCached).isEqualByComparingTo("3.00");
    }

    @Test
    void chat_offPeak_usesOffPeakColumns() {
        PricingRuleEntity r = chatRule();
        r.setOffPeakInputPerMillion(new BigDecimal("0.50"));
        r.setOffPeakOutputPerMillion(new BigDecimal("1.00"));
        when(pricingRuleMapper.findEffectiveWithResolution("CHAT", 1L, "gpt", false, null)).thenReturn(r);
        when(systemSettingService.getSettingValue(PricingService.OFF_PEAK_SCHEDULE_KEY))
                .thenReturn(OFF_PEAK_JSON);

        // 周三 23:30（跨零点窗口内）：1M in×0.5 + 1M out×1 = 1.50
        BigDecimal night = pricingService.computeCostAt("CHAT", 1L, "gpt",
                1_000_000, 1_000_000, null, null, false, null, null,
                java.time.LocalDateTime.of(2026, 8, 26, 23, 30));
        assertThat(night).isEqualByComparingTo("1.50");

        // 周三 12:00（窗口外）→ 忙时价 3.00
        BigDecimal noon = pricingService.computeCostAt("CHAT", 1L, "gpt",
                1_000_000, 1_000_000, null, null, false, null, null,
                java.time.LocalDateTime.of(2026, 8, 26, 12, 0));
        assertThat(noon).isEqualByComparingTo("3.00");
    }

    @Test
    void chat_offPeakColumnNull_fallsBackBusy() {
        PricingRuleEntity r = chatRule();
        // 只配闲时输出价，输入价 NULL → 夜间输入仍走忙时
        r.setOffPeakOutputPerMillion(new BigDecimal("1.00"));
        when(pricingRuleMapper.findEffectiveWithResolution("CHAT", 1L, "gpt", false, null)).thenReturn(r);
        when(systemSettingService.getSettingValue(PricingService.OFF_PEAK_SCHEDULE_KEY))
                .thenReturn(OFF_PEAK_JSON);

        BigDecimal night = pricingService.computeCostAt("CHAT", 1L, "gpt",
                1_000_000, 1_000_000, null, null, false, null, null,
                java.time.LocalDateTime.of(2026, 8, 26, 23, 30));
        // in 忙 1.0 + out 闲 1.0 = 2.00
        assertThat(night).isEqualByComparingTo("2.00");
    }

    @Test
    void chat_offPeakCached_chain() {
        PricingRuleEntity r = chatRule();
        r.setPriceCachedPerMillion(new BigDecimal("0.50"));
        r.setOffPeakCachedPerMillion(new BigDecimal("0.10"));
        when(pricingRuleMapper.findEffectiveWithResolution("CHAT", 1L, "gpt", false, null)).thenReturn(r);
        when(systemSettingService.getSettingValue(PricingService.OFF_PEAK_SCHEDULE_KEY))
                .thenReturn(OFF_PEAK_JSON);

        // 夜间：in×1（无闲时输入价）+ cached×0.10（闲时缓存价）
        BigDecimal night = pricingService.computeCostAt("CHAT", 1L, "gpt",
                1_000_000, 0, null, null, false, null, 1_000_000L,
                java.time.LocalDateTime.of(2026, 8, 26, 23, 30));
        assertThat(night).isEqualByComparingTo("1.10");

        // 白天：cached×0.50（忙时缓存价）
        BigDecimal day = pricingService.computeCostAt("CHAT", 1L, "gpt",
                1_000_000, 0, null, null, false, null, 1_000_000L,
                java.time.LocalDateTime.of(2026, 8, 26, 12, 0));
        assertThat(day).isEqualByComparingTo("1.50");
    }

    @Test
    void isOffPeak_matrix() {
        when(systemSettingService.getSettingValue(PricingService.OFF_PEAK_SCHEDULE_KEY))
                .thenReturn(OFF_PEAK_JSON);
        // 2026-08-26 周三 / 2026-08-29 周六
        assertThat(pricingService.isOffPeak(java.time.LocalDateTime.of(2026, 8, 26, 22, 0))).isTrue();   // 边界起点含
        assertThat(pricingService.isOffPeak(java.time.LocalDateTime.of(2026, 8, 27, 7, 59))).isTrue();  // 次日 07:59（周四凌晨仍在窗）
        assertThat(pricingService.isOffPeak(java.time.LocalDateTime.of(2026, 8, 27, 8, 0))).isFalse();   // 08:00 整出窗
        assertThat(pricingService.isOffPeak(java.time.LocalDateTime.of(2026, 8, 26, 12, 0))).isFalse(); // 工作日午间
        assertThat(pricingService.isOffPeak(java.time.LocalDateTime.of(2026, 8, 29, 10, 0))).isTrue();  // 周六全天
    }

    @Test
    void isOffPeak_disabledOrBroken_fallsBackBusy() {
        when(systemSettingService.getSettingValue(PricingService.OFF_PEAK_SCHEDULE_KEY))
                .thenReturn("{\"enabled\":false}")
                .thenReturn("not-json{")
                .thenReturn(null);
        assertThat(pricingService.isOffPeak(java.time.LocalDateTime.of(2026, 8, 26, 23, 30))).isFalse();
        assertThat(pricingService.isOffPeak(java.time.LocalDateTime.of(2026, 8, 26, 23, 30))).isFalse();
        assertThat(pricingService.isOffPeak(java.time.LocalDateTime.of(2026, 8, 26, 23, 30))).isFalse();
    }

    @Test
    void embed_ignoresCachedTokens() {
        // EMBED/RERANK：缓存腿不参与（传了也按 null 处理）
        PricingRuleEntity r = rule("EMBED");
        r.setPriceInputPerMillion(new BigDecimal("0.50"));
        when(pricingRuleMapper.findEffectiveWithResolution("EMBED", null, "e", false, null)).thenReturn(r);

        BigDecimal cost = pricingService.computeCost("EMBED", null, "e",
                2_000_000, 0, null, null, false, null, 999_999L);
        assertThat(cost).isEqualByComparingTo("1.00");
    }

    /**
     * D10 硬门槛回归矩阵：老价表（四个新列全 NULL + 闲时配置未启用）六 kind 计费
     * 与改前逐分一致。期望值即 V159 之前的口径，任何一行变动=老价表回归破坏。
     */
    @Test
    void legacy_pricing_allNewColumnsNull_matrix_sameAsBefore() {
        // CHAT：1M in×1 + 1M out×2
        PricingRuleEntity chat = chatRule();
        when(pricingRuleMapper.findEffectiveWithResolution("CHAT", 1L, "gpt", false, null)).thenReturn(chat);
        assertThat(pricingService.computeCost("CHAT", 1L, "gpt",
                1_000_000, 1_000_000, null, null, false, null, null))
                .isEqualByComparingTo("3.00");

        // EMBED：2M×0.5（output/缓存均忽略）
        PricingRuleEntity embed = rule("EMBED");
        embed.setPriceInputPerMillion(new BigDecimal("0.50"));
        when(pricingRuleMapper.findEffectiveWithResolution("EMBED", null, "e", false, null)).thenReturn(embed);
        assertThat(pricingService.computeCost("EMBED", null, "e",
                2_000_000, 999_999, null, null, false, null, 999_999L))
                .isEqualByComparingTo("1.00");

        // RERANK：2M×0.8
        PricingRuleEntity rerank = rule("RERANK");
        rerank.setPriceInputPerMillion(new BigDecimal("0.80"));
        when(pricingRuleMapper.findEffectiveWithResolution("RERANK", 9L, "r", false, null)).thenReturn(rerank);
        assertThat(pricingService.computeCost("RERANK", 9L, "r",
                2_000_000, 0, null, null, false, null, null))
                .isEqualByComparingTo("1.60");

        // VIDEO TOKEN：1M×3（秒数不看）
        PricingRuleEntity vt = rule("VIDEO");
        vt.setPriceInputPerMillion(new BigDecimal("3.00"));
        when(pricingRuleMapper.findEffectiveWithResolution("VIDEO", 7L, "seedance", false, null)).thenReturn(vt);
        assertThat(pricingService.computeCost("VIDEO", 7L, "seedance",
                1_000_000, null, 10, null, false))
                .isEqualByComparingTo("3.00");

        // VIDEO SECOND：通用行 0.5¥/秒 × 10s（带 resolution 的在途请求也命中通用行）
        PricingRuleEntity vs = rule("VIDEO");
        vs.setVideoBillingMode("SECOND");
        vs.setPricePerSecond(new BigDecimal("0.50"));
        when(pricingRuleMapper.findEffectiveWithResolution("VIDEO", 7L, "seedance", false, null)).thenReturn(vs);
        assertThat(pricingService.computeCost("VIDEO", 7L, "seedance",
                null, null, 10, null, false))
                .isEqualByComparingTo("5.00");

        // IMAGE：3 张 × 0.1
        PricingRuleEntity img = rule("IMAGE");
        img.setPricePerImage(new BigDecimal("0.10"));
        when(pricingRuleMapper.findEffectiveWithResolution("IMAGE", 2L, "seedream", false, null)).thenReturn(img);
        assertThat(pricingService.computeCost("IMAGE", 2L, "seedream",
                null, null, null, 3, false))
                .isEqualByComparingTo("0.30");
    }

    // ---------------- V164（MVR-3）：SECOND 秒价分档槽位（price_per_second_per_resolution） ----------------

    @Test
    void video_second_resolutionSlot_used() {
        // 2K 任务命中 2k 槽（0.2/秒 × 5s = 1.0）；"2K" 大写经 normalizeResolution 归一取键
        PricingRuleEntity row = rule("VIDEO");
        row.setVideoBillingMode(PricingRuleEntity.VIDEO_MODE_SECOND);
        row.setPricePerSecond(new BigDecimal("0.1"));
        row.setPricePerSecondPerResolution("{\"768p\":0.05,\"2k\":0.2}");
        when(pricingRuleMapper.findEffectiveWithResolution("VIDEO", 7L, "seedance", false, "2k"))
                .thenReturn(null);
        when(pricingRuleMapper.findEffectiveWithResolution("VIDEO", 7L, "seedance", false, null))
                .thenReturn(row);

        BigDecimal cost = pricingService.computeCost("VIDEO", 7L, "seedance",
                null, null, 5, 0, false, "2K");

        assertThat(cost).isEqualByComparingTo("1.000000");
    }

    @Test
    void video_second_unlistedSlot_fallsBackToGeneral() {
        // 480p 未配槽 → 回落通用秒价 0.1（零迁移兼容，与 V162 token 槽同语义）
        PricingRuleEntity row = rule("VIDEO");
        row.setVideoBillingMode(PricingRuleEntity.VIDEO_MODE_SECOND);
        row.setPricePerSecond(new BigDecimal("0.1"));
        row.setPricePerSecondPerResolution("{\"768p\":0.05,\"2k\":0.2}");
        when(pricingRuleMapper.findEffectiveWithResolution("VIDEO", 7L, "seedance", false, "480p"))
                .thenReturn(null);
        when(pricingRuleMapper.findEffectiveWithResolution("VIDEO", 7L, "seedance", false, null))
                .thenReturn(row);

        BigDecimal cost = pricingService.computeCost("VIDEO", 7L, "seedance",
                null, null, 5, 0, false, "480p");

        assertThat(cost).isEqualByComparingTo("0.500000");
    }

    @Test
    void video_second_dirtySlotJson_fallsBackToGeneral() {
        // 脏 JSON → WARN + 回落通用秒价，不炸结算（fail-open：脏价表不废任务）
        PricingRuleEntity row = rule("VIDEO");
        row.setVideoBillingMode(PricingRuleEntity.VIDEO_MODE_SECOND);
        row.setPricePerSecond(new BigDecimal("0.1"));
        row.setPricePerSecondPerResolution("{bad json");
        when(pricingRuleMapper.findEffectiveWithResolution("VIDEO", 7L, "seedance", false, "768p"))
                .thenReturn(null);
        when(pricingRuleMapper.findEffectiveWithResolution("VIDEO", 7L, "seedance", false, null))
                .thenReturn(row);

        BigDecimal cost = pricingService.computeCost("VIDEO", 7L, "seedance",
                null, null, 5, 0, false, "768p");

        assertThat(cost).isEqualByComparingTo("0.500000");
    }

    @Test
    void video_second_resolutionNull_ignoresSlots() {
        // 分辨率未传（旧链路/无参兜底）→ 不进槽，直取通用秒价
        PricingRuleEntity row = rule("VIDEO");
        row.setVideoBillingMode(PricingRuleEntity.VIDEO_MODE_SECOND);
        row.setPricePerSecond(new BigDecimal("0.1"));
        row.setPricePerSecondPerResolution("{\"2k\":0.2}");
        when(pricingRuleMapper.findEffectiveWithResolution("VIDEO", 7L, "seedance", false, null))
                .thenReturn(row);

        BigDecimal cost = pricingService.computeCost("VIDEO", 7L, "seedance",
                null, null, 5, 0, false, null);

        assertThat(cost).isEqualByComparingTo("0.500000");
    }

    @Test
    void video_second_slots_notConfusedWithEstSlots() {
        // 双 JSONB 撞脸 ×2 用例：est 槽（TOKEN 预估 ¥/秒）与 SECOND 槽（真实扣费 ¥/秒）互不串——
        // SECOND 行即使误配了 est_per_resolution，扣费/估价都只读 SECOND 槽
        PricingRuleEntity row = rule("VIDEO");
        row.setVideoBillingMode(PricingRuleEntity.VIDEO_MODE_SECOND);
        row.setPricePerSecond(new BigDecimal("0.1"));
        row.setEstPerResolution("{\"768p\":9.9}");
        row.setPricePerSecondPerResolution("{\"768p\":0.05}");
        when(pricingRuleMapper.findEffectiveWithResolution("VIDEO", 7L, "seedance", false, "768p"))
                .thenReturn(null);
        when(pricingRuleMapper.findEffectiveWithResolution("VIDEO", 7L, "seedance", false, null))
                .thenReturn(row);

        BigDecimal cost = pricingService.computeCost("VIDEO", 7L, "seedance",
                null, null, 5, 0, false, "768p");
        assertThat(cost).isEqualByComparingTo("0.250000"); // 0.05×5，非 9.9×5

        // 估价与扣费同口径（estimateVideoYuan SECOND 分支也取 SECOND 槽）
        assertThat(pricingService.estimateVideoYuan(7L, "seedance", 5, "768p", false))
                .isEqualByComparingTo("0.250000");
    }

    @Test
    void estimateVideoYuan_secondMode_usesSlotPrice() {
        // 估价 = SECOND 分档槽 ?? 通用秒价
        PricingRuleEntity row = rule("VIDEO");
        row.setVideoBillingMode(PricingRuleEntity.VIDEO_MODE_SECOND);
        row.setPricePerSecond(new BigDecimal("0.1"));
        row.setPricePerSecondPerResolution("{\"768p\":0.05}");
        when(pricingRuleMapper.findEffectiveWithResolution("VIDEO", 7L, "seedance", false, "768p"))
                .thenReturn(row);

        assertThat(pricingService.estimateVideoYuan(7L, "seedance", 5, "768p", false))
                .isEqualByComparingTo("0.250000");
    }
}
