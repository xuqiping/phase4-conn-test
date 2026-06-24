package com.superprogrammer.knowledge.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("knowledge_bases")
public class KnowledgeBase extends BaseEntity {

    private Long tenantId;

    private String name;

    private String description;

    /** PRIVATE / TEAM / PUBLIC */
    private String visibility;

    /** 标量 active embedding 模型 code，Phase1 = doubao */
    private String embeddingModel;

    private String rerankModel;

    private String chunkStrategy;

    /** L0 摘要生成模式：PER_SECTION / BATCH / HYBRID（阶段2 解析用，默认 PER_SECTION） */
    private String summaryStrategy;

    /** ACTIVE / ARCHIVED */
    private String status;

    private String effectivePartition;
}
