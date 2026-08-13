package com.superprogrammer.knowledge.dto;

import lombok.Data;

@Data
public class KnowledgeDocumentVersionActivateRequest {
    private Long expectedCurrentVersionId;
}
