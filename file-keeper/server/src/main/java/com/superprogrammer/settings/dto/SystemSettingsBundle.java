package com.superprogrammer.settings.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record SystemSettingsBundle(
        @NotNull @Min(1) Integer defaultDeviceLimit,
        @NotNull @Min(0) Integer defaultOfflineCacheMinutes,
        @NotNull @Min(1) Integer anonymousTrialDays,
        @NotNull @Min(1) Integer freeModuleChangeDays
) {
}
