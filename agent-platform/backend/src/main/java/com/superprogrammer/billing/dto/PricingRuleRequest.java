package com.superprogrammer.billing.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 价表创建/更新请求（admin 配置）。effectiveFrom 为空取 now（立即生效）。
 */
@Data
public class PricingRuleRequest {
    private String kind;          // CHAT/EMBED/IMAGE/VIDEO
    private Long providerId;      // null=全局价
    private String model;
    private BigDecimal priceInputPerMillion;
    private BigDecimal priceOutputPerMillion;
    private String videoBillingMode;  // TOKEN|SECOND（kind=VIDEO 时）
    private BigDecimal pricePerSecond;
    private BigDecimal pricePerImage;
    private OffsetDateTime effectiveFrom;
}
