package com.superprogrammer.device.dto;

import jakarta.validation.constraints.NotBlank;

public record SelectFreeModuleRequest(
        @NotBlank String deviceId,
        @NotBlank String fingerprintHash,
        @NotBlank String freeModuleCode
) {
}
