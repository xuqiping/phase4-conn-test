package com.superprogrammer.workreport.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record WorkLogDto(
        Long id,
        LocalDate logDate,
        String content,
        String tags,
        String source,
        Integer sortOrder,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
