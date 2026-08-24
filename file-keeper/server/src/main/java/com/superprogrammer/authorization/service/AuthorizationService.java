package com.superprogrammer.authorization.service;

import com.superprogrammer.authorization.dto.AnonymousAuthorizationSnapshot;
import com.superprogrammer.authorization.dto.AuthorizationSnapshot;
import com.superprogrammer.authorization.dto.ModuleAccess;
import com.superprogrammer.config.AuthProperties;
import com.superprogrammer.device.dto.DeviceDto;
import com.superprogrammer.device.repository.DeviceRepository;
import com.superprogrammer.security.AuthConstants;
import com.superprogrammer.user.entity.User;
import com.superprogrammer.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AuthorizationService {

    private static final List<String> MODULE_CODES = List.of(
            AuthConstants.MODULE_FILES,
            AuthConstants.MODULE_PROCESSES,
            AuthConstants.MODULE_CLIPBOARD,
            AuthConstants.MODULE_WORK_REPORT,
            AuthConstants.MODULE_AI
    );
    private static final long TIME_SYNC_ANOMALY_THRESHOLD_MS = 5 * 60 * 1000;

    private final UserRepository userRepository;
    private final DeviceRepository deviceRepository;
    private final AuthProperties authProperties;

    public AuthorizationSnapshot authenticatedSnapshot(Long userId, String deviceId, Long clientTimestamp) {
        User user = userRepository.requireById(userId);
        Optional<DeviceDto> device = deviceRepository.findByUserIdAndDeviceId(userId, deviceId);
        boolean deviceBound = device.isPresent();
        boolean deviceActive = device
                .map(value -> AuthConstants.STATUS_ACTIVE.equals(value.status()))
                .orElse(false);
        boolean accountActive = AuthConstants.STATUS_ACTIVE.equals(user.getStatus());
        boolean accessAllowed = accountActive && deviceBound && deviceActive;
        List<ModuleAccess> modules = MODULE_CODES.stream()
                .map(moduleCode -> compatibilityModuleAccess(
                        moduleCode, accessAllowed, accountActive, deviceBound, deviceActive))
                .toList();

        if (clientTimestamp != null && device.isPresent()) {
            checkTimeSyncAnomaly(device.get().id(), clientTimestamp);
        }

        return new AuthorizationSnapshot(
                "authenticated",
                user.getId(),
                user.getStatus(),
                user.getDeviceLimit(),
                true,
                null,
                buildOptionalCompatibilityToken(user.getId(), deviceId, modules),
                new AuthorizationSnapshot.DeviceBinding(deviceId, deviceBound, deviceActive),
                modules
        );
    }

    public AnonymousAuthorizationSnapshot anonymousSnapshot(String deviceId, String fingerprintHash) {
        List<ModuleAccess> modules = MODULE_CODES.stream()
                .map(moduleCode -> {
                    boolean localModule = AuthConstants.MODULE_FILES.equals(moduleCode)
                            || AuthConstants.MODULE_PROCESSES.equals(moduleCode)
                            || AuthConstants.MODULE_CLIPBOARD.equals(moduleCode);
                    return new ModuleAccess(moduleCode, localModule, localModule ? null : "请先登录", null);
                })
                .toList();
        return new AnonymousAuthorizationSnapshot("anonymous", true, deviceId, modules);
    }

    private ModuleAccess compatibilityModuleAccess(String moduleCode, boolean accessAllowed,
                                                   boolean accountActive, boolean deviceBound,
                                                   boolean deviceActive) {
        if (accessAllowed) {
            return new ModuleAccess(moduleCode, true, null, null);
        }
        String reason;
        if (!accountActive) {
            reason = "账号不可用";
        } else if (!deviceBound) {
            reason = "设备未绑定";
        } else if (!deviceActive) {
            reason = "设备已禁用";
        } else {
            reason = "访问不可用";
        }
        return new ModuleAccess(moduleCode, false, reason, null);
    }

    private String buildOptionalCompatibilityToken(Long userId, String deviceId, List<ModuleAccess> modules) {
        if (authProperties.getEntitlementPrivateKey() == null) {
            return null;
        }
        Instant issuedAt = Instant.now();
        Instant notAfter = issuedAt.plus(Duration.ofHours(authProperties.getJwt().getClientAccessTokenHours()));
        List<String> allowedModules = modules.stream()
                .filter(ModuleAccess::allowed)
                .map(ModuleAccess::moduleCode)
                .toList();
        return SignedEntitlementSigner.sign(
                authProperties.getEntitlementPrivateKey(),
                userId,
                deviceId,
                issuedAt,
                notAfter,
                allowedModules
        );
    }

    private void checkTimeSyncAnomaly(Long deviceId, Long clientTimestamp) {
        long diff = Math.abs(System.currentTimeMillis() - clientTimestamp);
        if (diff > TIME_SYNC_ANOMALY_THRESHOLD_MS) {
            deviceRepository.incrementTimeSyncAnomaly(deviceId);
        }
    }
}
