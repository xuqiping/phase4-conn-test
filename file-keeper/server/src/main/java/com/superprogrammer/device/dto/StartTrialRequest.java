package com.superprogrammer.device.dto;

import jakarta.validation.constraints.NotBlank;

public record StartTrialRequest(
        @NotBlank String deviceId,
        @NotBlank String fingerprintHash,
        String deviceName
) {
}
