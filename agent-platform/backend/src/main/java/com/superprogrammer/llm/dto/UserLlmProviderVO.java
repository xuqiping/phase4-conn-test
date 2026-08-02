package com.superprogrammer.llm.dto;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@Builder
public class UserLlmProviderVO {
    private Long id;
    private String providerName;
    private String apiEndpoint;
    private boolean hasApiKey;
    private String models;
    private String status;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
