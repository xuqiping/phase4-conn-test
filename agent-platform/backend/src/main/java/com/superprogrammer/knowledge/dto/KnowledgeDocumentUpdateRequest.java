package com.superprogrammer.knowledge.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;

/** Canonical Document 治理元数据；文件引用与内容 Hash 不允许由客户端修改。 */
@Data
public class KnowledgeDocumentUpdateRequest {
    private Long ownerId;
    @Size(max = 50)
    private String sourceType;
    @Size(max = 1024)
    private String sourceUri;
    private OffsetDateTime sourceUpdatedAt;
    private String authorityLevel;
    private String confidentialityLevel;
    private List<String> tags;
    private OffsetDateTime effectiveAt;
    private OffsetDateTime expiredAt;
}
