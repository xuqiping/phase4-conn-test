package com.superprogrammer.workreport.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

public record InboundMessageDto(
        Long id,
        Long userId,
        String platform,
        String platformMessageId,
        String senderId,
        String senderName,
        String rawText,
        String intent,
        BigDecimal confidence,
        Map<String, Object> parsedPayload,
        String status,
        String targetModule,
        Long targetId,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
