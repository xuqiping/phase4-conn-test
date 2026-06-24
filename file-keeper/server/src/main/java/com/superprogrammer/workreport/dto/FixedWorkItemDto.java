package com.superprogrammer.workreport.dto;

import java.time.LocalTime;
import java.time.OffsetDateTime;

public record FixedWorkItemDto(
        Long id,
        String content,
        String description,
        String recurrenceType,
        LocalTime reminderTime,
        String reminderDays,
        String timezone,
        Boolean reminderEnabled,
        String pushPlatform,
        String pushTargetId,
        Boolean hasCredential,
        Integer sortOrder,
        Boolean completedToday,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
