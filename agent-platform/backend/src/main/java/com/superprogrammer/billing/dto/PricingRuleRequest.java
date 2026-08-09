package com.superprogrammer.billing.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 价表创建/更新请求（admin 配置）。effectiveFrom 为空取 now（立即生效）。
 * <p>安全体系 S1 · SEC-FR-122：价格字段 Bean Validation 挡负数/超上限（上限 1 亿/百万token，
 * 防误填）；枚举与模式联动校验仍在 {@code PricingConfigService}（VO 边界之外的语义校验）。
 */
@Data
public class PricingRuleRequest {
    private String kind;          // CHAT/EMBED/IMAGE/VIDEO
    private Long providerId;      // null=全局价

    @Size(max = 100, message = "model 长度不能超过 100")
    private String model;

    @DecimalMin(value = "0", message = "priceInputPerMillion 须≥0")
    @DecimalMax(value = "100000000", message = "priceInputPerMillion 超出上限")
    private BigDecimal priceInputPerMillion;

    @DecimalMin(value = "0", message = "priceOutputPerMillion 须≥0")
    @DecimalMax(value = "100000000", message = "priceOutputPerMillion 超出上限")
    private BigDecimal priceOutputPerMillion;

    private String videoBillingMode;  // TOKEN|SECOND（kind=VIDEO 时）

    @DecimalMin(value = "0", message = "pricePerSecond 须≥0")
    @DecimalMax(value = "100000000", message = "pricePerSecond 超出上限")
    private BigDecimal pricePerSecond;

    @DecimalMin(value = "0", message = "pricePerImage 须≥0")
    @DecimalMax(value = "100000000", message = "pricePerImage 超出上限")
    private BigDecimal pricePerImage;

    private OffsetDateTime effectiveFrom;
}
