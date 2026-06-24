package com.superprogrammer.user.dto;

import java.time.OffsetDateTime;

public record UpdateEntitlementRequest(
        Boolean enabled,
        OffsetDateTime expiresAt
) {
}
