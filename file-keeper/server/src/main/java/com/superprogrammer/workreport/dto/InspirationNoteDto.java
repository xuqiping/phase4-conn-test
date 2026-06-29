package com.superprogrammer.workreport.dto;

import java.time.LocalDateTime;
import java.util.List;

public record InspirationNoteDto(
        Long id,
        String content,
        List<String> tags,
        String source,
        String platformMessageId,
        List<Long> reportConfigIds,
        LocalDateTime reviewedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
