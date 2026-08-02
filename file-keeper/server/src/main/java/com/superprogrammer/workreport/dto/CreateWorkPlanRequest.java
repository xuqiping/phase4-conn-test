package com.superprogrammer.workreport.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.time.LocalTime;

public record CreateWorkPlanRequest(
        @NotNull LocalDate planDate,
        @NotBlank String content,
        String description,
        String priority,
        LocalTime plannedStartTime,
        LocalTime plannedEndTime,
        Integer sortOrder
) {
}
