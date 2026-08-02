package com.superprogrammer.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * knowledge_index_jobs：Outbox，解耦写原文与写向量（v6 §6）。
 * 注意：该表无 deleted/version 列，**不继承 BaseEntity**。
 * insert 时 status/attempt/maxAttempt/contextHash/visibilityEvent/createdAt/updatedAt
 * 留 null → MyBatis-Plus FieldStrategy.NOT_NULL 省略 → 走 DB 默认值
 * （PENDING / 0 / 5 / __phase1_placeholder__ / false / NOW() / NOW()）。
 */
@Data
@TableName("knowledge_index_jobs")
public class KnowledgeIndexJob {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long nodeId;

    private Long kbId;

    /** UPSERT_L1（doc 级）job 用：node_id 为空时由此锚定文档。V36 新增。 */
    private Long documentId;

    /** UPSERT / DELETE / REINDEX / UPSERT_L1 */
    private String jobType;

    private String contentHash;

    private String contextHash;

    /** PENDING / RUNNING / DONE / FAILED / DEAD */
    private String status;

    private Integer attempt;

    private Integer maxAttempt;

    private OffsetDateTime lockedUntil;

    /** 不变式 I4：sha256(nodeId + ":" + contentHash + ":" + jobType)，唯一 */
    private String idempotencyKey;

    private Boolean visibilityEvent;

    private String lastError;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;
}
