package com.superprogrammer.workreport.dto;

import jakarta.validation.constraints.NotBlank;

public record PushCredentialCreateRequest(
        @NotBlank String name,
        @NotBlank String platform,
        String credential
) {
}
