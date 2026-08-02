package com.superprogrammer.authorization.dto;

import java.util.List;

public record AnonymousAuthorizationSnapshot(
        String mode,
        boolean onlineRequired,
        String deviceId,
        List<ModuleAccess> modules
) {
}
