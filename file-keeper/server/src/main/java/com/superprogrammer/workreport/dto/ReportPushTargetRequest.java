package com.superprogrammer.workreport.dto;

import jakarta.validation.constraints.NotBlank;

public record ReportPushTargetRequest(
        Long id,
        @NotBlank String platform,
        @NotBlank String targetType,
        @NotBlank String targetId,
        String credential
) {
}
