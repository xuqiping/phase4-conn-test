package com.superprogrammer.llm.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class LlmProviderCreateRequest {
    @NotBlank
    private String name;
    private String displayName;
    @NotBlank
    private String apiEndpoint;
    private String apiKey;
    private String models;
    private String config;
    private Integer sortOrder;
}
