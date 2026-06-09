package com.superprogrammer.authorization.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.OffsetDateTime;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuthorizationSnapshot(
        String mode,
        Long userId,
        String accountStatus,
        Integer deviceLimit,
        boolean onlineRequired,
        OffsetDateTime offlineUsableUntil,
        DeviceBinding deviceBinding,
        List<ModuleAccess> modules
) {
    public record DeviceBinding(String deviceId, boolean bound, boolean active) {
    }
}
