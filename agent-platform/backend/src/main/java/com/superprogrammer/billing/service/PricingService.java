package com.superprogrammer.billing.service;

import com.superprogrammer.billing.entity.PricingRuleEntity;
import com.superprogrammer.billing.mapper.PricingRuleMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 模型询价服务：按价表把用量折成 ¥（真实金额）。
 * <p>计费链第一步：token/秒/张 →(价表)→ ¥。第二步 ¥→积分 由 {@link PointsRatioService}。
 * <p>各 kind 口径：
 * <ul>
 *   <li>CHAT：(input/1M)×pin + (output/1M)×pout。</li>
 *   <li>EMBED：仅 input 计价（output=0）：(input/1M)×pin。</li>
 *   <li>VIDEO TOKEN 模式：视频总 token × priceInputPerMillion / 1M（caller 把 Ark 返的 total_tokens 传 tokensInput）。</li>
 *   <li>VIDEO SECOND 模式：videoSeconds × pricePerSecond。</li>
 *   <li>IMAGE：imageCount × pricePerImage。</li>
 * </ul>
 * <p>无价表抛 {@link ErrorCode#PRICING_NOT_FOUND}；调用层（gateway/worker）应 catch 降级为「不计费+WARN」，
 * 不阻断核心调用（计费配置缺失不应让对话/视频 500）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PricingService {

    private static final BigDecimal ONE_MILLION = new BigDecimal("1000000");

    private final PricingRuleMapper pricingRuleMapper;

    /**
     * 计算单次调用真实金额（¥）。
     *
     * @param kind          CHAT/EMBED/IMAGE/VIDEO
     * @param providerId    provider DB id（null 走全局价）
     * @param model         模型名
     * @param tokensInput   input token（VIDEO TOKEN 模式传视频总 token）
     * @param tokensOutput  output token（EMBED/VIDEO 不用）
     * @param videoSeconds  视频秒数（VIDEO SECOND 模式用）
     * @param imageCount    图片张数（IMAGE 用）
     * @return 真实金额 ¥（6 位小数，对齐 cost_yuan NUMERIC(12,6)）
     * @deprecated 使用 {@link #computeCost(String, Long, String, Integer, Integer, Integer, Integer, boolean)}
     *             显式传 hasReference。本重载恒按无参考计价，仅向后兼容旧调用点。
     */
    @Deprecated
    public BigDecimal computeCost(String kind, Long providerId, String model,
                                  Integer tokensInput, Integer tokensOutput,
                                  Integer videoSeconds, Integer imageCount) {
        return computeCost(kind, providerId, model, tokensInput, tokensOutput,
                videoSeconds, imageCount, false);
    }

    /**
     * 计算单次调用真实金额（¥），VIDEO 支持按 hasReference 区分参考视频价。
     *
     * @param hasReference  VIDEO 任务是否带参考视频（其他 kind 忽略，恒按 false 查）。
     *                      VIDEO 查不到精确行时回退 false 行（兜底），再查不到抛 PRICING_NOT_FOUND。
     */
    public BigDecimal computeCost(String kind, Long providerId, String model,
                                  Integer tokensInput, Integer tokensOutput,
                                  Integer videoSeconds, Integer imageCount,
                                  boolean hasReference) {
        return computeCost(kind, providerId, model, tokensInput, tokensOutput,
                videoSeconds, imageCount, hasReference, null);
    }

    /**
     * 7x-1（V152）：+resolution 版本。VIDEO SECOND 模式按分辨率行计价；
     * 其他 kind / VIDEO TOKEN 忽略（其行 resolution 恒 NULL，传值不命中也无妨——归一为 null 查）。
     * <p>VIDEO 命中链：精确(参考面,分辨率) → (参考面,通用NULL行) → (无参考,分辨率) → (无参考,通用)。
     */
    public BigDecimal computeCost(String kind, Long providerId, String model,
                                  Integer tokensInput, Integer tokensOutput,
                                  Integer videoSeconds, Integer imageCount,
                                  boolean hasReference, String resolution) {
        boolean isVideo = PricingRuleEntity.KIND_VIDEO.equals(kind);
        boolean effectiveHasRef = isVideo && hasReference;
        String effectiveResolution = isVideo ? normalizeResolution(resolution) : null;
        PricingRuleEntity rule = resolveRule(kind, providerId, model, effectiveHasRef, effectiveResolution);
        if (rule == null) {
            throw new BusinessException(ErrorCode.PRICING_NOT_FOUND,
                    "未配置价表: kind=" + kind + " providerId=" + providerId
                            + " model=" + model + " hasReference=" + hasReference
                            + " resolution=" + resolution);
        }
        return switch (kind) {
            case PricingRuleEntity.KIND_CHAT ->
                    textCost(rule, tokensInput, tokensOutput, true);
            case PricingRuleEntity.KIND_EMBED ->
                    textCost(rule, tokensInput, tokensOutput, false);
            case PricingRuleEntity.KIND_RERANK ->
                    textCost(rule, tokensInput, tokensOutput, false);
            case PricingRuleEntity.KIND_VIDEO -> videoCost(rule, tokensInput, videoSeconds);
            // resolution 已在 resolveRule 命中行时体现（SECOND 分辨率行/通用行），计价本身只看行内价格
            case PricingRuleEntity.KIND_IMAGE -> imageCost(rule, imageCount);
            default -> throw new BusinessException(ErrorCode.BAD_REQUEST, "未知计费 kind: " + kind);
        };
    }

    /**
     * 7x-1（V152）：VIDEO 价表行命中链——
     * 精确(参考面,分辨率) → (参考面,通用NULL行) → (无参考,分辨率) → (无参考,通用NULL行)。
     * 非 VIDEO 退化为原 (kind,provider,model,false,NULL) 单次精确查。
     */
    private PricingRuleEntity resolveRule(String kind, Long providerId, String model,
                                          boolean hasRef, String resolution) {
        PricingRuleEntity rule = pricingRuleMapper.findEffectiveWithResolution(
                kind, providerId, model, hasRef, resolution);
        if (rule == null && resolution != null) {
            // 该分辨率未单列 → 通用行兜底（admin 只配一行 NULL 即可覆盖所有分辨率）
            rule = pricingRuleMapper.findEffectiveWithResolution(kind, providerId, model, hasRef, null);
        }
        // 7x-3 fallback：有参考精确查不到 → 回退无参考行（分辨率同样先精确后通用）
        if (rule == null && hasRef) {
            rule = pricingRuleMapper.findEffectiveWithResolution(kind, providerId, model, false, resolution);
            if (rule == null && resolution != null) {
                rule = pricingRuleMapper.findEffectiveWithResolution(kind, providerId, model, false, null);
            }
        }
        return rule;
    }

    /** resolution 归一化：trim+小写（4K→4k，与价表落库口径一致）；空串视 null（=通用行）。 */
    static String normalizeResolution(String resolution) {
        if (resolution == null || resolution.isBlank()) {
            return null;
        }
        return resolution.trim().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * 7x-2（V152）：视频提交期估价（¥ 口径，caller 折积分）。
     * SECOND 模式 = 命中行 pricePerSecond × 时长（分辨率精确/通用兜底同真实扣费链）；
     * TOKEN 模式提交期无 token 维度 = 命中行 estYuanPerSecond × 时长。
     * 无价表行 / SECOND 秒价未配 / TOKEN 预估秒价未配 → 抛 {@link ErrorCode#PRICING_NOT_FOUND}
     * （2026-08-25 fail-closed：估不出价不许静默记 0——估价 0 会跳过预检与预扣放白嫖，
     *  提交侧必须拒单，caller 不得吞；预估预览除外，catch 记 0）。
     * 显式配 0 价（免费模型）返 0，由提交侧硬闸统一拒（免费请配极小正值）。
     */
    public BigDecimal estimateVideoYuan(Long providerId, String model,
                                        Integer seconds, String resolution, boolean hasReference) {
        PricingRuleEntity rule = resolveRule(PricingRuleEntity.KIND_VIDEO, providerId, model,
                hasReference, normalizeResolution(resolution));
        if (rule == null) {
            throw new BusinessException(ErrorCode.PRICING_NOT_FOUND,
                    "未配置价表: kind=VIDEO providerId=" + providerId + " model=" + model
                            + " hasReference=" + hasReference + " resolution=" + resolution);
        }
        if (seconds == null || seconds <= 0) {
            return BigDecimal.ZERO;
        }
        if (PricingRuleEntity.VIDEO_MODE_SECOND.equals(rule.getVideoBillingMode())) {
            if (rule.getPricePerSecond() == null) {
                throw new BusinessException(ErrorCode.PRICING_NOT_FOUND,
                        "VIDEO SECOND 模式未配置 price_per_second: model=" + model);
            }
            return rule.getPricePerSecond().multiply(BigDecimal.valueOf(seconds))
                    .setScale(6, RoundingMode.HALF_UP);
        }
        // TOKEN 模式：预估秒价（按分辨率参数，V153）× 时长（仅估价，不参与真实扣费）
        BigDecimal est = resolveEstPerSecond(rule.getEstPerResolution(),
                normalizeResolution(resolution));
        if (est == null) {
            throw new BusinessException(ErrorCode.PRICING_NOT_FOUND,
                    "VIDEO TOKEN 模式未配置 est_per_resolution 预估秒价: model=" + model
                            + " hasReference=" + hasReference + " resolution=" + resolution);
        }
        return est.multiply(BigDecimal.valueOf(seconds)).setScale(6, RoundingMode.HALF_UP);
    }

    /**
     * 7x-2（V153）：从 est_per_resolution JSON 取「任务分辨率对应预估值」——
     * 精确分辨率键 → general 兜底键 → null（未配置=不可估，caller 按 0 放行）。
     * JSON 损坏/值非法 → null + WARN（估价旁路绝不阻断提交）。
     */
    private static BigDecimal resolveEstPerSecond(String estPerResolutionJson, String resolution) {
        if (estPerResolutionJson == null || estPerResolutionJson.isBlank()) {
            return null;
        }
        try {
            java.util.Map<String, Object> map = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(estPerResolutionJson,
                            new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Object>>() { });
            Object v = resolution != null ? map.get(resolution) : null;
            if (v == null) {
                v = map.get("general");
            }
            if (v == null) {
                return null;
            }
            BigDecimal est = new BigDecimal(String.valueOf(v));
            return est.signum() >= 0 ? est : null;
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(PricingService.class)
                    .warn("est_per_resolution 解析失败按不可估处理: {}", e.getMessage());
            return null;
        }
    }

    /** 文本/embed 询价。billOutput=false（embed）时忽略 output。 */
    private BigDecimal textCost(PricingRuleEntity rule, Integer in, Integer out, boolean billOutput) {
        BigDecimal cost = BigDecimal.ZERO;
        if (in != null && in > 0 && rule.getPriceInputPerMillion() != null) {
            cost = cost.add(price(rule.getPriceInputPerMillion(), in));
        }
        if (billOutput && out != null && out > 0 && rule.getPriceOutputPerMillion() != null) {
            cost = cost.add(price(rule.getPriceOutputPerMillion(), out));
        }
        return cost;
    }

    /** 视频询价：TOKEN 模式按 token（×priceInputPerMillion），SECOND 模式按秒。 */
    private BigDecimal videoCost(PricingRuleEntity rule, Integer tokens, Integer seconds) {
        if (PricingRuleEntity.VIDEO_MODE_SECOND.equals(rule.getVideoBillingMode())) {
            if (seconds == null || seconds <= 0 || rule.getPricePerSecond() == null) {
                return BigDecimal.ZERO;
            }
            return rule.getPricePerSecond().multiply(BigDecimal.valueOf(seconds)).setScale(6, RoundingMode.HALF_UP);
        }
        // TOKEN 模式（默认）：视频总 token × priceInputPerMillion / 1M
        if (tokens == null || tokens <= 0 || rule.getPriceInputPerMillion() == null) {
            return BigDecimal.ZERO;
        }
        return price(rule.getPriceInputPerMillion(), tokens);
    }

    /** 图片询价：张数 × 单价。 */
    private BigDecimal imageCost(PricingRuleEntity rule, Integer count) {
        if (count == null || count <= 0 || rule.getPricePerImage() == null) {
            return BigDecimal.ZERO;
        }
        return rule.getPricePerImage().multiply(BigDecimal.valueOf(count)).setScale(6, RoundingMode.HALF_UP);
    }

    /** pricePerMillion × tokens / 1M。 */
    private BigDecimal price(BigDecimal pricePerMillion, int tokens) {
        return pricePerMillion
                .multiply(BigDecimal.valueOf(tokens))
                .divide(ONE_MILLION, 6, RoundingMode.HALF_UP);
    }
}
