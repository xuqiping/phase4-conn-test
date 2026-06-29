package com.superprogrammer.knowledge.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.OffsetDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("knowledge_documents")
public class KnowledgeDocument extends BaseEntity {

    private Long kbId;

    private Long directoryId;

    private String title;

    /** policy / manual / faq / api / ... */
    private String docType;

    /** PENDING / PARSING / SUMMARIZING / EMBEDDING / INDEXED / FAILED */
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

    private OffsetDateTime effectiveAt;

    private OffsetDateTime deadline;
}
