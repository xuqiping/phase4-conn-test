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

    /**
     * 7x-3：视频任务「是否带参考视频」的定价维度。仅 VIDEO kind 有效（true=配带参考视频的价）；
     * 非 VIDEO 强制 false（{@code PricingConfigService} 校验）。null 视为 false。
     */
    private Boolean hasReference;

    /**
     * 7x-1（V152）：分辨率定价维度，仅 VIDEO SECOND 有效（480p/720p/1080p/4k，大小写不敏感，
     * 落库统一小写）；null=通用行（未单列分辨率的兜底）。其他 kind / VIDEO TOKEN 必须为 null。
     */
    @Size(max = 16, message = "resolution 长度不能超过 16")
    private String resolution;

    /**
     * 7x-2（V153）：提交期预估秒价（一行多分辨率参数），仅 VIDEO TOKEN 模式有效——TOKEN
     * 提交期无 token 维度，余额预检用「任务分辨率对应值×时长」估价；真实扣费仍按 total_tokens。
     * 键 ⊆ general/480p/720p/1080p/4k（general=通用兜底；大小写不敏感，落库统一小写），值须≥0。
     * 其他 kind/SECOND 必须为 null（SECOND 估价直接用秒价行）。
     */
    private java.util.Map<String, BigDecimal> estPerResolution;

    /**
     * V162：TOKEN 每百万价按分辨率分档（一行多档），仅 VIDEO+TOKEN 模式有效——
     * 键 ⊆ 480p/720p/1080p/4k（无 general——通用/兜底价=priceInputPerMillion 列；大小写不敏感，落库统一小写），
     * 值=¥/百万 token 须>0；未配档位结算回落通用价。其他 kind/SECOND 必须为 null。
     */
    private java.util.Map<String, BigDecimal> tokenPricePerResolution;

    /**
     * D（V160）闲时/缓存四新列，仅 CHAT/EMBED/RERANK 有效（IMAGE/VIDEO 必须为 null）。
     * 全部可空：NULL=回落语义（闲时列空=同忙时价；缓存价空=同输入价）。
     */
    @DecimalMin(value = "0", message = "offPeakInputPerMillion 须≥0")
    @DecimalMax(value = "100000000", message = "offPeakInputPerMillion 超出上限")
    private BigDecimal offPeakInputPerMillion;

    @DecimalMin(value = "0", message = "offPeakOutputPerMillion 须≥0")
    @DecimalMax(value = "100000000", message = "offPeakOutputPerMillion 超出上限")
    private BigDecimal offPeakOutputPerMillion;

    @DecimalMin(value = "0", message = "offPeakCachedPerMillion 须≥0")
    @DecimalMax(value = "100000000", message = "offPeakCachedPerMillion 超出上限")
    private BigDecimal offPeakCachedPerMillion;

    @DecimalMin(value = "0", message = "priceCachedPerMillion 须≥0")
    @DecimalMax(value = "100000000", message = "priceCachedPerMillion 超出上限")
    private BigDecimal priceCachedPerMillion;

    private OffsetDateTime effectiveFrom;
}
