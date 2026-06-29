package com.superprogrammer.ai.dto;

public record AiConfigVO(
        Long id,
        String name,
        String provider,
        String model,
        String endpoint,
        Integer maxTokens,
        Integer timeoutSeconds,
        Boolean isDefault,
        Boolean enabled
) {
}
