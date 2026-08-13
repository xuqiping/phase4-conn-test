package com.superprogrammer.knowledge.dto;

import lombok.Data;

/**
 * RAG 检索查询结果行（MyBatis-Plus map-underscore-to-camel-case 自动映射列→字段）。
 * 仅 mapper 内部 + service 使用。
 */
public class RagQueryRow {

    /** dense L0 召回结果行 */
    @Data
    public static class DenseRecallRow {
        private Long nodeId;
        private Long documentId;
        private String title;
        /** L0 摘要原文（node.content），调试面板「候选 L0」展示用，区分 Sheet:行N 占位 title */
        private String content;
        /** pgvector cosine 距离 [0,2]，sim = 1 - distance */
        private Double cosineDistance;
    }

    /** dense L1 文档召回结果行（Phase3，doc 级语义锚） */
    @Data
    public static class L1RecallRow {
        private Long documentId;
        private String title;
        /** pgvector cosine 距离 [0,2]，sim = 1 - distance */
        private Double cosineDistance;
    }

    /** L2 候选行（children 与 BM25 复用；bm25Rank 仅 BM25 命中时非空）*/
    @Data
    public static class L2Row {
        private Long nodeId;
        private Long documentId;
        private Long parentId;
        private String title;
        private String content;
        private String contentHash;
        private Double bm25Rank;
    }

    /** I3 evidence content_hash 复校行 */
    @Data
    public static class HashVerifyRow {
        private Long id;
        private String nodeHash;
        private String embedHash;
        private String metadata;
    }

    /** L1 文档元数据行（含 IMAGE/FILE 原件回显字段 file_ref/mime/original_name，LEFT JOIN stored_files）。 */
    @Data
    public static class L1Row {
        private Long id;
        private String title;
        private String docType;
        private String l1Metadata;
        private String authorityLevel;
        private String confidentialityLevel;
        private java.time.OffsetDateTime effectiveAt;
        private java.time.OffsetDateTime expiredAt;
        /** IMAGE/FILE 原件引用（/api/files/{fileId}），回显用；普通文档也可能有但回显时按 docType 判断。 */
        private String fileRef;
        private String mime;
        private String originalName;
    }
}
