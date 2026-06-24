package com.superprogrammer.llm.dto;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@Builder
public class LlmProviderVO {
    private Long id;
    private String name;
    private String displayName;
    private String protocol;
    private String apiEndpoint;
    private String models;
    private String config;
    private String status;
    private Integer sortOrder;
    private String category;
    /** 只读：向量维度（仅 category=EMBEDDING 有值，取自 embedding_model_versions ACTIVE 行）。 */
    private Integer dim;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
