package com.superprogrammer.knowledge.dto;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * C6 连接器列表/详情（WP6 Step1）。**不含 config 明文**——凭证只写不读，
 * 前端仅展示类型/状态/同步摘要；重配=整体重提交表单。
 */
@Data
@Builder
public class KnowledgeConnectorVO {

    private Long id;
    private Long kbId;
    /** URL_SITE / S3 / WEBDAV */
    private String type;
    private String name;
    /** ENABLED / DISABLED / ERROR */
    private String status;
    private String scheduleCron;
    private boolean syncOnSourceDelete;
    private OffsetDateTime lastSyncAt;
    /** 最近一轮摘要（新增/更新/删除/错误计数，脱敏）。 */
    private String lastSyncSummary;
    /** 连续同步错误轮数（WP6 Step4，≥3 即 ERROR——前端红标展开用）。 */
    private Integer syncErrorStreak;
    private OffsetDateTime createdAt;
}
