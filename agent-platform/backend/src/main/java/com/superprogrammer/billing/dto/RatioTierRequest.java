package com.superprogrammer.billing.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 阶梯比例创建/更新请求。区间 [min, max)，max 空=∞。
 * <p>校验：跨当前生效集不重叠不漏（首档 min=0，相邻 max=下档 min，末档 max=∞）。
 * <p>安全体系 S1 · SEC-FR-122：Bean Validation 挡负数/零/超上限；
 * 区间关系（max>min）与整集连续性校验仍在 {@code PricingConfigService}。
 */
@Data
public class RatioTierRequest {

    /** 区间下界（含），≥0。 */
    @NotNull(message = "minAmount 不能为空")
    @DecimalMin(value = "0", message = "minAmount 须≥0")
    @DecimalMax(value = "100000000", message = "minAmount 超出上限")
    private BigDecimal minAmount;

    /** 区间上界（不含），null=∞；非空须为正。 */
    @DecimalMin(value = "0.01", message = "maxAmount 须为正或留空(=∞)")
    @DecimalMax(value = "100000000", message = "maxAmount 超出上限")
    private BigDecimal maxAmount;

    /** ¥→积分 比例（>0）。 */
    @NotNull(message = "ratio 不能为空")
    @DecimalMin(value = "0.000001", message = "ratio 须>0")
    @DecimalMax(value = "100000000", message = "ratio 超出上限")
    private BigDecimal ratio;

    private OffsetDateTime effectiveFrom;
}
