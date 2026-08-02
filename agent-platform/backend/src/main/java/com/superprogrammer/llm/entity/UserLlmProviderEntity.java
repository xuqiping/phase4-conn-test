package com.superprogrammer.llm.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("user_llm_providers")
public class UserLlmProviderEntity extends BaseEntity {

    private Long userId;
    private String providerName;
    private String apiEndpoint;
    private String apiKeyEnc;
    private String models;
    private String status;
}
