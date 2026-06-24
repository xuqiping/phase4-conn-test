package com.superprogrammer.workreport.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;

public record UpdateFuturePlanRequest(
        @NotBlank String content,
        String description,
        @NotNull OffsetDateTime scheduledAt,
        String timezone,
        Boolean reminderEnabled,
        Integer reminderMinutesBefore,
        String pushPlatform,
        String pushTargetId,
        String pushCredential,
        Integer sortOrder
) {
}
