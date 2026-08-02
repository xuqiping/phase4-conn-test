package com.superprogrammer.workreport.dto;

import jakarta.validation.constraints.NotBlank;

public record UpdateWorkLogRequest(
        @NotBlank String content,
        String tags,
        String source,
        Integer sortOrder
) {
}
