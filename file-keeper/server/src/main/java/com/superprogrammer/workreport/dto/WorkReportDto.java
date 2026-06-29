package com.superprogrammer.workreport.dto;

import java.time.LocalDateTime;

public record WorkReportDto(
        Long id,
        String reportType,
        String title,
        String content,
        LocalDateTime generatedAt,
        String status,
        Double completionRate,
        Integer consecutiveMissDays
) {
}
