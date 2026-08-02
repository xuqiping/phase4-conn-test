package com.superprogrammer.knowledge.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 知识节点 VO（目录树用，flat 列表，前端按 parentId 建树）。
 * 暴露 content（L0 摘要 / L2 原文）供前端「查看文档内容」展示；
 * contentHash 为内部不变式用，不暴露。
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

    /** L0 摘要 / L2 原文片段（前端展开行渲染全文）。 */
    private String content;

    private Integer tokenCount;

    /** ACTIVE / STALE / ARCHIVED */
    private String status;
}
