package com.superprogrammer.device.service;

import com.superprogrammer.common.BusinessException;
import com.superprogrammer.common.ErrorCode;
import com.superprogrammer.device.dto.AnonymousTrialStatusDto;
import com.superprogrammer.device.dto.SelectFreeModuleRequest;
import com.superprogrammer.device.dto.StartTrialRequest;
import com.superprogrammer.device.repository.AnonymousTrialRepository;
import com.superprogrammer.device.repository.AnonymousTrialRepository.AnonymousTrialRecord;
import com.superprogrammer.security.AuthConstants;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class AnonymousTrialService {

    private static final List<String> ALL_MODULES = List.of(
            AuthConstants.MODULE_FILES,
            AuthConstants.MODULE_PROCESSES,
            AuthConstants.MODULE_CLIPBOARD
    );
    private static final Set<String> VALID_MODULES = Set.copyOf(ALL_MODULES);

    private final AnonymousTrialRepository anonymousTrialRepository;

    public AnonymousTrialStatusDto start(StartTrialRequest request) {
        var existing = anonymousTrialRepository.findByDeviceId(request.deviceId());
        if (existing.isPresent()) {
            return toStatus(requireUsable(existing.get(), request.fingerprintHash()));
        }
        OffsetDateTime now = OffsetDateTime.now();
        AnonymousTrialRecord record = anonymousTrialRepository.insert(
                request.deviceId(),
                request.fingerprintHash(),
                request.deviceName(),
                now,
                now.plusDays(AuthConstants.ANONYMOUS_FULL_TRIAL_DAYS)
        );
        return toStatus(record);
    }

    public AnonymousTrialStatusDto status(String deviceId, String fingerprintHash) {
        AnonymousTrialRecord record = anonymousTrialRepository.findByDeviceId(deviceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "匿名试用记录不存在"));
        return toStatus(requireUsable(record, fingerprintHash));
    }

    public AnonymousTrialStatusDto selectFreeModule(SelectFreeModuleRequest request) {
        validateModuleCode(request.freeModuleCode());
        AnonymousTrialRecord record = requireExistingUsable(request.deviceId(), request.fingerprintHash());
        if (isInFullTrial(record)) {
            throw new BusinessException(ErrorCode.UNPROCESSABLE, "试用期内不能选择免费模块");
        }
        if (record.freeModuleCode() != null) {
            throw new BusinessException(ErrorCode.UNPROCESSABLE, "已选择免费模块，请使用更换接口");
        }
        return toStatus(anonymousTrialRepository.updateFreeModule(request.deviceId(), request.freeModuleCode(), OffsetDateTime.now()));
    }

    public AnonymousTrialStatusDto changeFreeModule(SelectFreeModuleRequest request) {
        validateModuleCode(request.freeModuleCode());
        AnonymousTrialRecord record = requireExistingUsable(request.deviceId(), request.fingerprintHash());
        if (isInFullTrial(record)) {
            throw new BusinessException(ErrorCode.UNPROCESSABLE, "试用期内不能更换免费模块");
        }
        if (record.freeModuleCode() == null) {
            throw new BusinessException(ErrorCode.UNPROCESSABLE, "请先选择免费模块");
        }
        OffsetDateTime nextAllowedAt = record.lastFreeModuleChangedAt().plusDays(AuthConstants.ANONYMOUS_FREE_MODULE_CHANGE_DAYS);
        if (OffsetDateTime.now().isBefore(nextAllowedAt)) {
            throw new BusinessException(ErrorCode.UNPROCESSABLE, "免费模块每 30 天只能更换一次");
        }
        return toStatus(anonymousTrialRepository.updateFreeModule(request.deviceId(), request.freeModuleCode(), OffsetDateTime.now()));
    }

    private AnonymousTrialRecord requireExistingUsable(String deviceId, String fingerprintHash) {
        AnonymousTrialRecord record = anonymousTrialRepository.findByDeviceId(deviceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "匿名试用记录不存在"));
        return requireUsable(record, fingerprintHash);
    }

    private AnonymousTrialRecord requireUsable(AnonymousTrialRecord record, String fingerprintHash) {
        if (!record.fingerprintHash().equals(fingerprintHash)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "设备指纹不匹配");
        }
        if (AuthConstants.STATUS_DISABLED.equals(record.status())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "匿名设备已禁用");
        }
        return record;
    }

    private void validateModuleCode(String moduleCode) {
        if (!VALID_MODULES.contains(moduleCode)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "模块代码必须是 files、processes 或 clipboard");
        }
    }

    private AnonymousTrialStatusDto toStatus(AnonymousTrialRecord record) {
        boolean inFullTrial = isInFullTrial(record);
        boolean trialExpired = !inFullTrial;
        List<String> allowedModuleCodes;
        if (inFullTrial) {
            allowedModuleCodes = ALL_MODULES;
        } else if (record.freeModuleCode() != null) {
            allowedModuleCodes = List.of(record.freeModuleCode());
        } else {
            allowedModuleCodes = List.of();
        }
        return new AnonymousTrialStatusDto(
                record.deviceId(),
                record.deviceName(),
                record.trialStartedAt(),
                record.trialExpiresAt(),
                inFullTrial,
                trialExpired,
                record.freeModuleCode(),
                record.freeModuleSelectedAt(),
                record.lastFreeModuleChangedAt(),
                allowedModuleCodes
        );
    }

    private boolean isInFullTrial(AnonymousTrialRecord record) {
        return record.trialExpiresAt().isAfter(OffsetDateTime.now());
    }
}
