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
    private String fileRef;
    private String fileHash;
    private String parseError;
    /** 解析选项 JSON（Excel selectedSheets 等），前端展示已选 sheet（V39）。 */
    private String parseOptions;
    /** 非致命解析告警（Excel 截断/降级），前端黄色徽章；与 parseError（致命 FAILED）并列（V39）。 */
    private String parseWarning;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
