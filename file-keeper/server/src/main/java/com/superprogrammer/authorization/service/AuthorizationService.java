package com.superprogrammer.authorization.service;

import com.superprogrammer.authorization.dto.AnonymousAuthorizationSnapshot;
import com.superprogrammer.authorization.dto.AuthorizationSnapshot;
import com.superprogrammer.authorization.dto.ModuleAccess;
import com.superprogrammer.device.dto.DeviceDto;
import com.superprogrammer.device.repository.AnonymousTrialRepository;
import com.superprogrammer.device.repository.AnonymousTrialRepository.AnonymousTrialRecord;
import com.superprogrammer.device.repository.DeviceRepository;
import com.superprogrammer.security.AuthConstants;
import com.superprogrammer.user.dto.ModuleEntitlementDto;
import com.superprogrammer.user.entity.User;
import com.superprogrammer.user.repository.EntitlementRepository;
import com.superprogrammer.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AuthorizationService {

    private static final List<String> MODULE_CODES = List.of(
            AuthConstants.MODULE_FILES,
            AuthConstants.MODULE_PROCESSES,
            AuthConstants.MODULE_CLIPBOARD
    );

    private final UserRepository userRepository;
    private final EntitlementRepository entitlementRepository;
    private final DeviceRepository deviceRepository;
    private final AnonymousTrialRepository anonymousTrialRepository;

    public AuthorizationSnapshot authenticatedSnapshot(Long userId, String deviceId) {
        User user = userRepository.requireById(userId);
        Optional<DeviceDto> device = deviceRepository.findByUserIdAndDeviceId(userId, deviceId);
        boolean deviceBound = device.isPresent();
        boolean deviceActive = device.map(value -> AuthConstants.STATUS_ACTIVE.equals(value.status())).orElse(false);
        boolean accountActive = AuthConstants.STATUS_ACTIVE.equals(user.getStatus());
        Map<String, ModuleEntitlementDto> entitlements = entitlementRepository.findActiveByUserId(userId).stream()
                .collect(Collectors.toMap(ModuleEntitlementDto::moduleCode, Function.identity()));
        List<ModuleAccess> modules = MODULE_CODES.stream()
                .map(moduleCode -> authenticatedModuleAccess(moduleCode, entitlements.get(moduleCode), accountActive, deviceBound, deviceActive))
                .toList();
        boolean onlineRequired = user.getOfflineCacheMinutes() == 0;
        return new AuthorizationSnapshot(
                "authenticated",
                user.getId(),
                user.getStatus(),
                user.getDeviceLimit(),
                onlineRequired,
                onlineRequired ? null : OffsetDateTime.now().plusMinutes(user.getOfflineCacheMinutes()),
                new AuthorizationSnapshot.DeviceBinding(deviceId, deviceBound, deviceActive),
                modules
        );
    }

    public AnonymousAuthorizationSnapshot anonymousSnapshot(String deviceId, String fingerprintHash) {
        Optional<AnonymousTrialRecord> optionalRecord = anonymousTrialRepository.findByDeviceId(deviceId);
        List<ModuleAccess> modules;
        if (optionalRecord.isEmpty()) {
            modules = denyAll("未开始试用");
        } else {
            AnonymousTrialRecord record = optionalRecord.get();
            modules = anonymousModules(record, fingerprintHash);
        }
        return new AnonymousAuthorizationSnapshot("anonymous", true, deviceId, modules);
    }

    private ModuleAccess authenticatedModuleAccess(String moduleCode, ModuleEntitlementDto entitlement,
                                                   boolean accountActive, boolean deviceBound, boolean deviceActive) {
        if (!accountActive) {
            return new ModuleAccess(moduleCode, false, "账号不可用", null);
        }
        if (!deviceBound) {
            return new ModuleAccess(moduleCode, false, "设备未绑定", null);
        }
        if (!deviceActive) {
            return new ModuleAccess(moduleCode, false, "设备已禁用", null);
        }
        if (entitlement == null) {
            return new ModuleAccess(moduleCode, false, "模块未授权或已过期", null);
        }
        return new ModuleAccess(moduleCode, true, null, entitlement.expiresAt());
    }

    private List<ModuleAccess> anonymousModules(AnonymousTrialRecord record, String fingerprintHash) {
        if (!record.fingerprintHash().equals(fingerprintHash)) {
            return denyAll("设备指纹不匹配");
        }
        if (AuthConstants.STATUS_DISABLED.equals(record.status())) {
            return denyAll("匿名设备已禁用");
        }
        if (record.trialExpiresAt().isAfter(OffsetDateTime.now())) {
            return MODULE_CODES.stream()
                    .map(moduleCode -> new ModuleAccess(moduleCode, true, null, record.trialExpiresAt()))
                    .toList();
        }
        if (record.freeModuleCode() == null) {
            return denyAll("试用期已结束，请选择免费模块");
        }
        return MODULE_CODES.stream()
                .map(moduleCode -> {
                    boolean allowed = moduleCode.equals(record.freeModuleCode());
                    return new ModuleAccess(moduleCode, allowed, allowed ? null : "非当前免费模块", null);
                })
                .toList();
    }

    private List<ModuleAccess> denyAll(String reason) {
        return MODULE_CODES.stream()
                .map(moduleCode -> new ModuleAccess(moduleCode, false, reason, null))
                .toList();
    }
}
