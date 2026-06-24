package com.superprogrammer.workreport.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalTime;

public record UpdateFixedWorkItemRequest(
        @NotBlank String content,
        String description,
        @NotBlank String recurrenceType,
        @NotNull LocalTime reminderTime,
        String reminderDays,
        String timezone,
        Boolean reminderEnabled,
        String pushPlatform,
        String pushTargetId,
        String pushCredential,
        Integer sortOrder
) {
}
