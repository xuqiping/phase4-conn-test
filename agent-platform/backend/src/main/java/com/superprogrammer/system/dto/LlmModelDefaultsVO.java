package com.superprogrammer.system.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class LlmModelDefaultsVO {
    private String chatModel;
    private String embeddingModel;
}
