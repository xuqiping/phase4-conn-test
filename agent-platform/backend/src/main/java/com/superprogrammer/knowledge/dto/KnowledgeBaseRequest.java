package com.superprogrammer.knowledge.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class KnowledgeBaseRequest {

    @NotBlank
    private String name;

    private String description;

    /** PRIVATE / TEAM / PUBLIC，默认 PRIVATE */
    private String visibility;

    /** Phase1 默认 doubao */
    private String embeddingModel;

    private String rerankModel;

    /** L0 摘要模式：PER_SECTION / BATCH / HYBRID，留空走默认 */
    private String summaryStrategy;
}
