package com.superprogrammer.user.dto;

import jakarta.validation.constraints.NotBlank;

public record CheckVerificationRequest(
        @NotBlank String contactType,
        @NotBlank String contact,
        @NotBlank String code
) {
}
