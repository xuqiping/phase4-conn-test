package com.superprogrammer.user.dto;

import jakarta.validation.constraints.NotBlank;

public record SendVerificationRequest(
        @NotBlank String contactType,
        @NotBlank String contact
) {
}
