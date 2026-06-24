package com.superprogrammer.workreport.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

public record CreateWorkLogRequest(
        @NotNull LocalDate logDate,
        @NotBlank String content,
        String tags,
        String source,
        Integer sortOrder
) {
}
