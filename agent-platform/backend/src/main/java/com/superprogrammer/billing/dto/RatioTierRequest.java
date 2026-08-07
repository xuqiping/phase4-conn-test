package com.superprogrammer.billing.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 阶梯比例创建/更新请求。区间 [min, max)，max 空=∞。
 * <p>校验：跨当前生效集不重叠不漏（首档 min=0，相邻 max=下档 min，末档 max=∞）。
 */
@Data
public class RatioTierRequest {
    private BigDecimal minAmount;
    private BigDecimal maxAmount;  // null=∞
    private BigDecimal ratio;
    private OffsetDateTime effectiveFrom;
}
