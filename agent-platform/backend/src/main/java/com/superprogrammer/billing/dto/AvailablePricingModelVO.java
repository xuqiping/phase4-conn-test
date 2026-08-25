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
    /** 7x-1：VIDEO 已配另一参考维度时的提示（本次新增的是哪个维度的价行）。 */
    private String hint;
    /** 7x-1（V152）：VIDEO 候选的参考视频维度（true=本候选配「有参考」价行）；非 VIDEO 恒 false。 */
    private Boolean hasReference;
    /** 7x-1（V152）：VIDEO 候选的分辨率槽位（null=通用行；480p/720p/1080p/4k）；非 VIDEO 恒 null。 */
    private String resolution;
}
