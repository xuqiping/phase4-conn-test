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
    /** 7x-2（V152）：VIDEO TOKEN 提交期预估秒价 ¥/秒（仅预检，不计费）；其他行恒 null。 */
    private BigDecimal estYuanPerSecond;
    private OffsetDateTime effectiveFrom;
}
