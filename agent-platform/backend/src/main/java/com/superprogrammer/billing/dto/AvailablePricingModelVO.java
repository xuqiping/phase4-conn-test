package com.superprogrammer.billing.dto;

import lombok.Builder;
import lombok.Data;

/** 管理员新增价表时可选择的全局模型最小信息。 */
@Data
@Builder
public class AvailablePricingModelVO {
    private Long providerId;
    private String providerName;
    private String model;
    private String kind;
}
