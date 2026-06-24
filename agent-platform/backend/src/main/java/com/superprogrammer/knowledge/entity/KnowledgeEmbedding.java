package com.superprogrammer.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * knowledge_embeddings_doubao：L0 dense 向量行（v6 §8.3）。
 * 非 BaseEntity（表无 deleted/version/created_by 等列）。
 *
 * **不映射 embedding(halfvec) 列**：halfvec 无法用 MyBatis-Plus 默认 handler，
 * 写入走 {@link com.superprogrammer.knowledge.mapper.KnowledgeEmbeddingMapper#upsert} 的
 * `#{halfvec}::halfvec` 自定义 SQL；本实体仅承载标量列（查询/调试用）。
 *
 * 不变式 I1：content_hash 必须 = node.content_hash（worker upsert 时写入，检索时复校）。
 */
@Data
@TableName("knowledge_embeddings_doubao")
public class KnowledgeEmbedding {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long nodeId;

    private Long tenantId;

    private Long kbId;

    /** 固定 L0（L2 不向量化，D1） */
    private String nodeLevel;

    private String embeddingModel;

    // embedding(halfvec(2048)) 不映射 —— 走自定义 SQL

    private String externalVectorId;

    /** JSON */
    private String metadata;

    private String contentHash;

    private String contextHash;

    private OffsetDateTime createdAt;
}
