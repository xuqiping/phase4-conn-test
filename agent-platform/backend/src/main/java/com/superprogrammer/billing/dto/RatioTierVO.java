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
public class RatioTierVO {
    private Long id;
    private BigDecimal minAmount;
    private BigDecimal maxAmount;
    private BigDecimal ratio;
    private OffsetDateTime effectiveFrom;
}
