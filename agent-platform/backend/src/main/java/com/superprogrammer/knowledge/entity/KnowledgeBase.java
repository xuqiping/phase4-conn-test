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

    /** 14x#1：per-KB RAG 问答模型 code，NULL=跟随全局默认（仅问答链路，索引摘要不走此列） */
    private String answerModel;

    /** 14x#3：库级保密开关，TRUE 时非 owner/admin 仅 RAG 问答出口（Step 4 咽喉判定用） */
    private Boolean confidential;

    private String chunkStrategy;

    /** L0 摘要生成模式：PER_SECTION / BATCH / HYBRID（阶段2 解析用，默认 PER_SECTION） */
    private String summaryStrategy;

    /** ACTIVE / ARCHIVED */
    private String status;

    private String effectivePartition;
}
