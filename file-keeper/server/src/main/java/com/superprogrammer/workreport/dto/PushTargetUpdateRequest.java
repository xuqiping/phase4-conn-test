package com.superprogrammer.workreport.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record PushTargetUpdateRequest(
        @NotBlank String name,
        @NotBlank String platform,
        @NotBlank String targetType,
        @NotBlank String targetId,
        @NotNull Long credentialId
) {
}
