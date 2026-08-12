package com.superprogrammer.system.dto;

import lombok.Data;

@Data
public class LlmModelDefaultsUpdateRequest {
    private String chatModel;
    private String embeddingModel;
}
