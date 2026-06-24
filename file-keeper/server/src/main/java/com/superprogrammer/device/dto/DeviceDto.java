package com.superprogrammer.device.dto;

import java.time.OffsetDateTime;

public record DeviceDto(
        Long id,
        Long userId,
        String deviceId,
        String fingerprintHash,
        String deviceName,
        String status,
        OffsetDateTime lastSeenAt,
        Integer timeSyncAnomalyCount
) {
}
