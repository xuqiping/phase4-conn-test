package com.superprogrammer.workreport.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record CreateInspirationNoteRequest(
        @NotBlank String content,
        List<String> tags,
        String source,
        String platformMessageId,
        List<Long> reportConfigIds
) {
}
