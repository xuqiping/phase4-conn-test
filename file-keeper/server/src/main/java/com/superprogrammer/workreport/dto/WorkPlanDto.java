package com.superprogrammer.workreport.dto;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;

public record WorkPlanDto(
        Long id,
        LocalDate planDate,
        String content,
        String description,
        String priority,
        LocalTime plannedStartTime,
        LocalTime plannedEndTime,
        Boolean completed,
        Integer sortOrder,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
