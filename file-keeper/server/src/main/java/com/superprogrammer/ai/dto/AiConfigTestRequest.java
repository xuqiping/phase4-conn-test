package com.superprogrammer.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record AiConfigTestRequest(
        @NotBlank String provider,
        @NotBlank String model,
        @NotBlank String apiKey,
        String endpoint,
        @NotNull @Positive Integer maxTokens,
        @NotNull @Positive Integer timeoutSeconds
) {
}
