package com.superprogrammer.llm.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("llm_providers")
public class LlmProviderEntity extends BaseEntity {
    private String name;
    private String displayName;
    private String protocol;
    private String apiEndpoint;
    private String apiKeyEnc;
    private String models;
    private String config;
    private String status;
    private Integer sortOrder;
    /** CHAT / EMBEDDING / CHAT_EMBEDDING。驱动测试分流 + 前端 badge。null→CHAT。 */
    private String category;
}
