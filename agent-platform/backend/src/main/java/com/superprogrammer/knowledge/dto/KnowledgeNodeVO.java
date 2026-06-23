package com.superprogrammer.knowledge.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 知识节点 VO（目录树用，flat 列表，前端按 parentId 建树）。
 * 不暴露 content（L2 原文可能大）+ contentHash（内部不变式用）。
 */
@Data
@Builder
public class KnowledgeNodeVO {

    private Long id;

    private Long parentId;

    private Long documentId;

    /** L0（摘要）/ L2（原文）；L1 在 documents 表 */
    private String level;

    /** DIRECTORY / SECTION / TABLE / FAQ */
    private String nodeType;

    private String title;

    private Integer tokenCount;

    /** ACTIVE / STALE / ARCHIVED */
    private String status;
}
