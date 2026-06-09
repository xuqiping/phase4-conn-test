package com.superprogrammer.user.dto;

import jakarta.validation.constraints.NotBlank;

import java.time.OffsetDateTime;

public record GrantEntitlementRequest(
        @NotBlank String moduleCode,
        OffsetDateTime expiresAt
) {
}
