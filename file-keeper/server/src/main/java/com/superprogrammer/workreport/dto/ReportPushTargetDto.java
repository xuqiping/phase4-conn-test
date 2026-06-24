package com.superprogrammer.workreport.dto;

public record ReportPushTargetDto(
        Long id,
        String platform,
        String targetType,
        String targetId,
        Boolean hasCredential
) {
}
