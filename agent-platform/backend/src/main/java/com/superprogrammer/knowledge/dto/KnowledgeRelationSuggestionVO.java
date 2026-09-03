package com.superprogrammer.knowledge.dto;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * C1 关联建议视图（规格 §3.3）。两端恒 docIdA < docIdB（对内无方向语义），
 * 采纳时由用户决定方向与类型（见 RelationSuggestionAdoptRequest）。
 */
@Data
@Builder
public class KnowledgeRelationSuggestionVO {

    private Long id;

    private Long kbId;

    private Long docIdA;

    private Long docIdB;

    /** A 端文档标题（已删则该建议不返回——悬挂过滤，对齐 listByDoc 惯例） */
    private String docTitleA;

    private String docTitleB;

    /** 窗口内共召回次数（越高越值得看） */
    private Integer coRecallCount;

    /** 最近共召回 query 的 hash（脱敏展示，运维排查用） */
    private String sampleQueryHash;

    /** PENDING / ADOPTED / IGNORED（列表仅返回 PENDING） */
    private String status;

    private OffsetDateTime lastSeenAt;

    private OffsetDateTime createdAt;
}
