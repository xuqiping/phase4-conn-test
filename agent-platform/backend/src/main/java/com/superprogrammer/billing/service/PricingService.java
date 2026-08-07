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
     */
    public BigDecimal computeCost(String kind, Long providerId, String model,
                                  Integer tokensInput, Integer tokensOutput,
                                  Integer videoSeconds, Integer imageCount) {
        PricingRuleEntity rule = pricingRuleMapper.findEffective(kind, providerId, model);
        if (rule == null) {
            throw new BusinessException(ErrorCode.PRICING_NOT_FOUND,
                    "未配置价表: kind=" + kind + " providerId=" + providerId + " model=" + model);
        }
        return switch (kind) {
            case PricingRuleEntity.KIND_CHAT ->
                    textCost(rule, tokensInput, tokensOutput, true);
            case PricingRuleEntity.KIND_EMBED ->
                    textCost(rule, tokensInput, tokensOutput, false);
            case PricingRuleEntity.KIND_VIDEO -> videoCost(rule, tokensInput, videoSeconds);
            case PricingRuleEntity.KIND_IMAGE -> imageCost(rule, imageCount);
            default -> throw new BusinessException(ErrorCode.BAD_REQUEST, "未知计费 kind: " + kind);
        };
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
