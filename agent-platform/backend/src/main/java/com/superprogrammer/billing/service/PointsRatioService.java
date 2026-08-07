package com.superprogrammer.billing.service;

import com.superprogrammer.billing.entity.PointsRatioTierEntity;
import com.superprogrammer.billing.mapper.PointsRatioTierMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 阶梯比例服务：¥ → 积分。
 * <p>计费链第二步：{@link PricingService} 算出 ¥ → 本服务按阶梯比例折积分。
 * <p>充值与消耗共用一套比例（spec §5 决策 4）。命中 min&lt;=¥&lt;(max||∞) 当前生效档。
 * <p>无阶梯配置时用兜底比例 {@code billing.default-ratio}（默认 100 pt/¥）+ WARN，
 * 不抛错（计费配置缺失不应阻断调用，与 PricingService 降级一致）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PointsRatioService {

    private final PointsRatioTierMapper tierMapper;

    /** 兜底比例：无阶梯配置时每 ¥ 换多少积分。 */
    @Value("${billing.default-ratio:100}")
    private BigDecimal defaultRatio;

    /**
     * ¥ → 积分。
     *
     * @param yuan 真实金额（来自 {@link PricingService#computeCost}）
     * @return 积分（2 位小数，对齐 balance/ledger NUMERIC(14,2)）
     */
    public BigDecimal toPoints(BigDecimal yuan) {
        if (yuan == null || yuan.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        PointsRatioTierEntity tier = tierMapper.findTier(yuan);
        BigDecimal ratio;
        if (tier != null && tier.getRatio() != null) {
            ratio = tier.getRatio();
        } else {
            log.warn("未配置积分阶梯比例，用兜底 ratio={} 折算 ¥={}", defaultRatio, yuan);
            ratio = defaultRatio;
        }
        return yuan.multiply(ratio).setScale(2, RoundingMode.HALF_UP);
    }
}
