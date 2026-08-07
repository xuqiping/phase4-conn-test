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
    /** CHAT / EMBEDDING / VIDEO / IMAGE（V60 起四分）。驱动测试分流 + 前端 badge + 路由过滤。null→CHAT。 */
    private String category;
}
