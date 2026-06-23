package com.superprogrammer.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * knowledge_reconciliation_reports（V17 已建表）—— 周期对账报告（v6 §7.3.6 最小对账）。
 * 表无 deleted/version/created_by/updated_by → 不继承 BaseEntity（同 KnowledgeIndexJob）。
 * 每次 ReconciliationJob 扫一个 KB 插一行：total/drift/orphan/stale/dead 计数 + repaired。
 */
@Data
@TableName("knowledge_reconciliation_reports")
public class KnowledgeReconciliationReport {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long kbId;
    private OffsetDateTime scannedAt;
    private Integer totalNodes;
    private Integer driftCount;
    private Integer orphanCount;
    private Integer staleWithEmbedding;
    private Integer repairedCount;
    private Integer deadJobCount;
    private OffsetDateTime createdAt;
}
