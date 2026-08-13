package com.superprogrammer.knowledge.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 知识库覆盖或管理员默认的重排配置。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("rag_ranking_configs")
public class RagRankingConfig extends BaseEntity {
    private Long tenantId;
    private Long kbId;
    private String rankingMode;
    private String model;
    private Integer candidateLimit;
    private Integer finalLimit;
    private Integer batchSize;
    private Integer timeoutMs;
    private String fallbackPolicy;
    private Boolean highAccuracyEnabled;
    private String configVersion;
    private String status;
}

