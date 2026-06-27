package com.superprogrammer.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record AiConfigCreateRequest(
        @NotBlank String name,
        @NotBlank String provider,
        @NotBlank String model,
        String apiKey,
        String endpoint,
        @NotNull @Positive Integer maxTokens,
        @NotNull @Positive Integer timeoutSeconds,
        Boolean isDefault,
        Boolean enabled
) {
}
