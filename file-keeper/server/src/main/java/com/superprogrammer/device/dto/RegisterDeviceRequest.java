package com.superprogrammer.device.dto;

import jakarta.validation.constraints.NotBlank;

public record RegisterDeviceRequest(
        @NotBlank String deviceId,
        @NotBlank String fingerprintHash,
        String deviceName
) {
}
