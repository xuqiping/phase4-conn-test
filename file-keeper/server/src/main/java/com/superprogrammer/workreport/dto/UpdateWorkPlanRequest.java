package com.superprogrammer.workreport.dto;

import jakarta.validation.constraints.NotBlank;

import java.time.LocalTime;

public record UpdateWorkPlanRequest(
        @NotBlank String content,
        String description,
        String priority,
        LocalTime plannedStartTime,
        LocalTime plannedEndTime,
        Boolean completed,
        Integer sortOrder
) {
}
