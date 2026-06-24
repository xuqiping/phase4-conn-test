package com.superprogrammer.workreport.dto;

import jakarta.validation.constraints.NotBlank;

public record SaveReportTemplateRequest(
        @NotBlank String name,
        @NotBlank String type,
        @NotBlank String content,
        Boolean isDefault
) {
}
