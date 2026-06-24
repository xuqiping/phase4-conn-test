package com.superprogrammer.device.dto;

import java.time.OffsetDateTime;

public record AnonymousDeviceDto(
        Long id,
        String deviceId,
        String fingerprintHash,
        String deviceName,
        String status,
        OffsetDateTime trialStartedAt,
        OffsetDateTime trialExpiresAt,
        String freeModuleCode,
        OffsetDateTime freeModuleSelectedAt,
        OffsetDateTime lastFreeModuleChangedAt,
        OffsetDateTime lastSeenAt,
        String firstSeenIp,
        String userAgentHash,
        int trialResetCount
) {
}
