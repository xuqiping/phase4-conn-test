package com.superprogrammer.workreport.dto;

import java.time.OffsetDateTime;

public record FuturePlanDto(
        Long id,
        String content,
        String description,
        OffsetDateTime scheduledAt,
        String timezone,
        Boolean reminderEnabled,
        Integer reminderMinutesBefore,
        String pushPlatform,
        String pushTargetId,
        Boolean hasCredential,
        String status,
        Integer sortOrder,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
