package com.superprogrammer.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * knowledge_doc_embeddings_doubao：L1 文档级向量行（V36，Phase3）。
 * 每文档 1 行：L1 文本（summary+outline+importantRules 拼接）embed 后的向量，作 doc 级语义锚参与召回。
 * 非 BaseEntity（表无 deleted/version/created_by 等列）。
 *
 * **不映射 embedding(halfvec) 列**：halfvec 无法用 MyBatis-Plus 默认 handler，
 * 写入走 {@link com.superprogrammer.knowledge.mapper.KnowledgeDocEmbeddingMapper#upsert} 的
 * `#{halfvec}::halfvec` 自定义 SQL；本实体仅承载标量列。
 */
@Data
@TableName("knowledge_doc_embeddings_doubao")
public class KnowledgeDocEmbedding {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long documentId;

    private Long tenantId;

    private Long kbId;

    private String embeddingModel;

    // embedding(halfvec(2048)) 不映射 —— 走自定义 SQL

    /** L1 文本 sha256（embed 时算）；召回时不复校（无 node 可比对） */
    private String contentHash;

    private OffsetDateTime createdAt;
}
