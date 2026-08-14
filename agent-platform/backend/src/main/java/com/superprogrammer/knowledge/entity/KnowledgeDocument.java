package com.superprogrammer.knowledge.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.superprogrammer.common.entity.BaseEntity;
import com.superprogrammer.common.typehandler.JsonbStringTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.OffsetDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "knowledge_documents", autoResultMap = true)
public class KnowledgeDocument extends BaseEntity {

    private Long kbId;

    private Long directoryId;

    private String title;

    /** policy / manual / faq / api / ... */
    private String docType;

    /** PENDING / PARSING / SUMMARIZING / EMBEDDING / INDEXED / FAILED / QUARANTINED（S3 注入隔离） */
    private String status;

    private Long currentVersionId;

    /** JSON: summary / outline / importantRules */
    private String l1Metadata;

    private String fileRef;

    private String fileHash;

    /** 解析失败原因，status=FAILED 时写入（V21） */
    private String parseError;

    /** 解析选项 JSON（Excel sheet 选择等）。空=默认行为。{ "selectedSheets": [...] }（V39） */
    private String parseOptions;

    /** 非致命解析告警（截断/降级），前端黄色徽章；与 parseError（致命 FAILED）并列（V39） */
    private String parseWarning;

    /** 隔离原因（安全体系 S3 · SEC-FR-051）：status=QUARANTINED 时写入，命中注入特征描述；解除时清空（V122） */
    private String quarantineReason;

    private OffsetDateTime effectiveAt;

    /** 失效时间；历史 deadline 字段由 V107 迁移到此语义明确的字段。 */
    private OffsetDateTime expiredAt;

    private Long ownerId;

    private String sourceType;

    private String sourceUri;

    private OffsetDateTime sourceUpdatedAt;

    /** OFFICIAL / APPROVED / REFERENCE / UNVERIFIED */
    private String authorityLevel;

    /** PUBLIC / INTERNAL / CONFIDENTIAL / RESTRICTED */
    private String confidentialityLevel;

    /** JSON 字符串数组，最多 20 个标签，每个最多 64 字符。 */
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String tags;

    /** @deprecated 使用 expiredAt；保留兼容旧列，V107 后不再写入。 */
    @Deprecated
    private OffsetDateTime deadline;
}
