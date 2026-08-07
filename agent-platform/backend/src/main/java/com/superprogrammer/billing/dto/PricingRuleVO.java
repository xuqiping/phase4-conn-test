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
    private OffsetDateTime effectiveFrom;
}
