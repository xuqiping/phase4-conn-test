package com.superprogrammer.knowledge.dto;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * C1 关系边视图（按单文档视角）。direction：
 *   OUT = 本文档为主动方（doc_id=本档）；
 *   IN  = 本文档为被动方（related_doc_id=本档）——检索语义按反向解释读
 *         （入边 MUST_BE_CITED(A→本档) 读作「命中本档时 A 强制出现」）。
 */
@Data
@Builder
public class KnowledgeRelationVO {

    private Long id;

    private Long kbId;

    /** OUT / IN */
    private String direction;

    /** 关系类型（原始存储值） */
    private String relationType;

    /** 另一端文档 id */
    private Long otherDocId;

    /** 另一端文档标题（前端直显，免二次查询） */
    private String otherDocTitle;

    private String note;

    private Long createdBy;

    private OffsetDateTime createdAt;
}
