package com.superprogrammer.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/** Canonical Document 的不可变版本；内容字段写入后只允许切换治理状态。 */
@Data
@TableName("knowledge_document_versions")
public class KnowledgeDocumentVersion {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long documentId;
    private Integer versionNo;
    private Long parentVersionId;
    private String contentHash;
    private String sourceHash;
    private String fileRef;
    private String parserVersion;
    private String parseArtifactRef;
    private String parseArtifactHash;
    private OffsetDateTime parsedAt;
    private String changeNote;
    private OffsetDateTime effectiveAt;
    private String status;
    private OffsetDateTime revokedAt;
    private Long revokedBy;
    private Long replacedByVersionId;
    private Long createdBy;
    private OffsetDateTime createdAt;
}
