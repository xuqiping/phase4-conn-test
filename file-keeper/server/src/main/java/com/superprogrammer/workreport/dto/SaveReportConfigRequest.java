package com.superprogrammer.workreport.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record SaveReportConfigRequest(
        Long id,
        @NotBlank String name,
        @NotBlank String reportType,
        @NotNull Long templateId,
        @NotBlank String cronExpression,
        String timezone,
        Boolean enabled,
        Boolean aiEnabled,
        Long aiConfigId,
        List<ReportPushTargetRequest> pushTargets
) {
}
