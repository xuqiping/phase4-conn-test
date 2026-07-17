package com.superprogrammer.workreport.dto;

import java.util.List;

public record ReportConfigDto(
        Long id,
        String name,
        String reportType,
        Long templateId,
        String templateName,
        String cronExpression,
        String timezone,
        Boolean enabled,
        Boolean aiEnabled,
        Long aiConfigId,
        Boolean includeInspirationDigest,
        Boolean inspirationReviewEnabled,
        List<PushTargetDto> pushTargets
) {
}
