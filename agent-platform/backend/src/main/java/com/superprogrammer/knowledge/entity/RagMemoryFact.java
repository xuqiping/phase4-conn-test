package com.superprogrammer.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * rag_memory_facts：M2 语义软提示（同义/改写模板/偏好/域提示，v6 §8.9）。
 * 非 BaseEntity（表无 deleted/version/created_by 等列）。
 *
 * <p>**key_embedding(halfvec) 列不映射**：halfvec 无法用 MyBatis-Plus 默认 handler，
 * 写入/检索须走自定义 SQL 的 `::halfvec`（本实体仅承载标量列）。
 *
 * <p>当前无生产者写入该表（M2 软提示特性未启用），实体+mapper 先就位供
 * {@link com.superprogrammer.knowledge.mapper.RagMemoryFactMapper#deleteDecayed} decay 兜底
 * （阶段7 ReconciliationJob sibling purge，对齐 rag_answer_cache）。
 */
@Data
@TableName("rag_memory_facts")
public class RagMemoryFact {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tenantId;

    /** JSON 数组文本，如 "[1,2]" */
    private String kbIds;

    /** synonym/rewrite_template/preference/domain_hint（无 cached_answer，D7） */
    private String factType;

    private String key;

    // key_embedding(halfvec(2048)) 不映射 —— 走自定义 SQL

    private String keyEmbeddingModel;

    private String value;

    /** JSON 数组文本 */
    private String provenanceNodeIds;

    /** JSON 数组文本 */
    private String provenanceEpisodeIds;

    private Float confidence;

    private Integer usageCount;

    private OffsetDateTime decayAt;

    /** 空 = 租户级 */
    private Long scopeUserId;

    /** ACTIVE/DISABLED/ARCHIVED */
    private String status;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;
}
