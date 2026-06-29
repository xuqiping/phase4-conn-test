package com.superprogrammer.ai.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ai_configs")
public class AiConfig extends BaseEntity {

    private Long userId;

    private String name;

    private String provider;

    private String model;

    private String apiKeyEnc;

    private String endpoint;

    private Integer maxTokens;

    private Integer timeoutSeconds;

    private Boolean isDefault;

    private Boolean enabled;
}
