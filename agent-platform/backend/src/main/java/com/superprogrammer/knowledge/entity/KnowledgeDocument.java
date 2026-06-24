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

    private OffsetDateTime effectiveAt;

    private OffsetDateTime deadline;
}
