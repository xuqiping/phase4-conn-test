package com.superprogrammer.knowledge.dto;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@Builder
public class KnowledgeDocumentVO {

    private Long id;
    private Long kbId;
    private String title;
    private String docType;
    private String status;
    private Long currentVersionId;
    private String fileRef;
    private String fileHash;
    /** IMAGE/FILE 原件回显用（mime 决定缩略图/下载；originalName 展示文件名）。null=普通文档。 */
    private String mime;
    private String originalName;
    /** 索引方式 MANUAL/AUTO（从 parse_options 解出，便于前端列表显徽章）。null=旧文档/未指定。 */
    private String indexMode;
    private String parseError;
    /** 解析选项 JSON（Excel selectedSheets 等），前端展示已选 sheet（V39）。 */
    private String parseOptions;
    /** 非致命解析告警（Excel 截断/降级），前端黄色徽章；与 parseError（致命 FAILED）并列（V39）。 */
    private String parseWarning;
    private Long ownerId;
    private String sourceType;
    private String sourceUri;
    private OffsetDateTime sourceUpdatedAt;
    private String authorityLevel;
    private String confidentialityLevel;
    private java.util.List<String> tags;
    private OffsetDateTime effectiveAt;
    private OffsetDateTime expiredAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
