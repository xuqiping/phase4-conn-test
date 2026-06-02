package com.superprogrammer.llm.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class UserLlmProviderRequest {

    @NotBlank
    private String providerName;
    private String apiEndpoint;
    private String apiKey;
    private String models;
}
