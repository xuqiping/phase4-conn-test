package com.superprogrammer.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * rag_answer_cache：跨会话语义答案缓存（v6 §8.9a，阶段4-B）。
 * 非 BaseEntity（表无 deleted/version/created_by/updated_by）。
 *
 * <p>**不映射 key_embedding(halfvec) 列**：halfvec 无法用 MyBatis-Plus 默认 handler，
 * 写入走 {@link com.superprogrammer.knowledge.mapper.RagAnswerCacheMapper#insert} 的
 * `#{keyHalf}::halfvec` 自定义 SQL；本实体仅承载标量列。
 *
 * <p>**强制 per-user**：scope_user_id NOT NULL，检索 SQL 恒带 `scope_user_id = ?` 过滤，
 * 跨用户近邻由 HNSW 返回也会被 WHERE 滤掉（P2/P3 校验链前的一道硬隔离）。
 */
@Data
@TableName("rag_answer_cache")
public class RagAnswerCache {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tenantId;

    /** 强制 per-user，非空 */
    private Long scopeUserId;

    /** JSON：[kbId,...] */
    private String kbIds;

    private String queryCanonical;

    // key_embedding(halfvec(2048)) 不映射 —— 走自定义 SQL

    private String keyEmbeddingModel;

    /** JSON：CachedPayload（answer 或 systemPrompt + citations + injectedIndexes）*/
    private String answer;

    /** JSON：[nodeId,...]（P2a hash 复校锚点）*/
    private String provenanceNodeIds;

    /** JSON：[hash,...]，与 provenanceNodeIds 平行（P2a 比对 node 现值）*/
    private String evidenceHashes;

    /** sha256(visible_set + kb_scope)，P3 权限变更检测 */
    private String permissionSignature;

    /** unused（P2a hash 已覆盖内容变更），留空 */
    @TableField("doc_version_set")
    private String docVersionSet;

    private Float confidence;

    private Integer usageCount;

    private OffsetDateTime decayAt;

    /** ACTIVE / DISABLED / ARCHIVED / REVOKED */
    private String status;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;
}
