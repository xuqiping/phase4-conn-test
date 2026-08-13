package com.superprogrammer.knowledge.dto;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@Builder
public class KnowledgeDocumentVersionVO {
    private Long id;
    private Long documentId;
    private Integer versionNo;
    private Long parentVersionId;
    private String sourceHash;
    private String fileRef;
    private String changeNote;
    private String status;
    private OffsetDateTime effectiveAt;
    private OffsetDateTime revokedAt;
    private Long replacedByVersionId;
    private OffsetDateTime createdAt;
}
