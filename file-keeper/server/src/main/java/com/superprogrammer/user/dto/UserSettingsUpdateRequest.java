package com.superprogrammer.user.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record UserSettingsUpdateRequest(
        @NotNull @Min(1) Integer deviceLimit,
        @NotNull @Min(0) Integer offlineCacheMinutes
) {
}
