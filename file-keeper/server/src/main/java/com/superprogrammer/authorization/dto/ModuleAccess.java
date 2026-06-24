package com.superprogrammer.authorization.dto;

import java.time.OffsetDateTime;

public record ModuleAccess(
        String moduleCode,
        boolean allowed,
        String reason,
        OffsetDateTime expiresAt
) {
}
