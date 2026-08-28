package com.superprogrammer.billing.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PricingRuleVO {
    private Long id;
    private String kind;
    private Long providerId;
    private String model;
    private BigDecimal priceInputPerMillion;
    private BigDecimal priceOutputPerMillion;
    private String videoBillingMode;
    private BigDecimal pricePerSecond;
    private BigDecimal pricePerImage;
    /** 7x-3：VIDEO 行才有意义（true=带参考视频价）；其他 kind 始终 false。 */
    private Boolean hasReference;
    /** 7x-1（V152）：VIDEO SECOND 分辨率行（null=通用兜底）；其他行恒 null。 */
    private String resolution;
    /** 7x-2（V153）：VIDEO TOKEN 提交期预估秒价（general/480p/720p/768p/1080p/2k/4k → ¥/秒；仅预检，不计费）；其他行恒 null。 */
    private java.util.Map<String, BigDecimal> estPerResolution;
    /** V162：VIDEO TOKEN 每百万价分辨率档（480p/720p/768p/1080p/2k/4k → ¥/百万；未配档回落通用价）；其他行恒 null。 */
    private java.util.Map<String, BigDecimal> tokenPricePerResolution;
    /** V164（MVR-3）：VIDEO SECOND 秒价分辨率档（480p/720p/768p/1080p/2k/4k → ¥/秒；未配档回落通用秒价）；其他行恒 null。 */
    private java.util.Map<String, BigDecimal> pricePerSecondPerResolution;
    /** D（V160）闲时/缓存四新列；仅 CHAT/EMBED/RERANK 行有意义，其他恒 null（NULL=回落语义）。 */
    private BigDecimal offPeakInputPerMillion;
    private BigDecimal offPeakOutputPerMillion;
    private BigDecimal offPeakCachedPerMillion;
    private BigDecimal priceCachedPerMillion;
    private OffsetDateTime effectiveFrom;
}
