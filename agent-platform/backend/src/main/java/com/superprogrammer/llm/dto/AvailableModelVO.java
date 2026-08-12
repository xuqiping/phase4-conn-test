package com.superprogrammer.llm.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AvailableModelVO {
    private String modelId;
    private String displayName;
    private String providerName;
    private String source;
    /** 是否为管理员配置的该类型默认模型。 */
    private Boolean defaultModel;
}
