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
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
