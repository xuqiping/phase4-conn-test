package com.superprogrammer.device.dto;

import java.time.OffsetDateTime;
import java.util.List;

public record AnonymousTrialStatusDto(
        String deviceId,
        String deviceName,
        OffsetDateTime trialStartedAt,
        OffsetDateTime trialExpiresAt,
        Boolean inFullTrial,
        Boolean trialExpired,
        String freeModuleCode,
        OffsetDateTime freeModuleSelectedAt,
        OffsetDateTime lastFreeModuleChangedAt,
        List<String> allowedModuleCodes
) {
}
