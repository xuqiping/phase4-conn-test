package com.superprogrammer.user.dto;

import java.time.OffsetDateTime;

public record ModuleEntitlementDto(
        Long id,
        Long userId,
        String moduleCode,
        Boolean enabled,
        OffsetDateTime expiresAt
) {
}
