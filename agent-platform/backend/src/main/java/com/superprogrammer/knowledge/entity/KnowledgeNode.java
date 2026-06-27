package com.superprogrammer.knowledge.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("knowledge_nodes")
public class KnowledgeNode extends BaseEntity {

    private Long tenantId;

    private Long kbId;

    private Long documentId;

    private Long parentId;

    private String path;

    /** DIRECTORY / SECTION / TABLE / FAQ */
    private String nodeType;

    /** L0 / L2（L1 移至 documents） */
    private String level;

    private String title;

    /** L0 摘要 / L2 原文（唯一真相源）。content_tsv 为 generated 列，不映射 */
    private String content;

    /** jieba 分词空格拼串（Phase2 BM25 词法兜底）。content_tokens_tsv 为 generated 列，不映射 */
    private String contentTokens;

    /** JSON */
    private String metadata;

    private Integer tokenCount;

    private String contentHash;

    private String contextHash;

    /** ACTIVE / STALE / ARCHIVED */
    private String status;

    private Long versionId;
}
