package com.superprogrammer.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * C1 关联建议（trace 共召回统计产出，只建议不自动建边）。硬删/状态推进，不继承 BaseEntity。
 */
@Data
@TableName("knowledge_document_relation_suggestions")
public class KnowledgeDocumentRelationSuggestion {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_ADOPTED = "ADOPTED";
    public static final String STATUS_IGNORED = "IGNORED";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tenantId;

    private Long kbId;

    /** 对内无方向语义，恒 docIdA < docIdB（约束 ck_kdrs_pair 去重同一对两方向） */
    private Long docIdA;

    private Long docIdB;

    /** 共召回次数 */
    private Integer coRecallCount;

    /** 最近一次共召回 query 的 hash（脱敏：对齐 trace 只存 hash 惯例） */
    private String sampleQueryHash;

    /** PENDING / ADOPTED / IGNORED */
    private String status;

    private OffsetDateTime lastSeenAt;

    private OffsetDateTime createdAt;
}
