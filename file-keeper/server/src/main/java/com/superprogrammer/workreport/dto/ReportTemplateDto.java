package com.superprogrammer.workreport.dto;

public record ReportTemplateDto(
        Long id,
        String name,
        String type,
        String content,
        Boolean isDefault
) {
}
