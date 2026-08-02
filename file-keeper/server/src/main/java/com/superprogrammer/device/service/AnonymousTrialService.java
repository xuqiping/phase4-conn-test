package com.superprogrammer.device.service;

import com.superprogrammer.common.BusinessException;
import com.superprogrammer.common.ErrorCode;
import com.superprogrammer.device.dto.AnonymousTrialStatusDto;
import com.superprogrammer.device.dto.SelectFreeModuleRequest;
import com.superprogrammer.device.dto.StartTrialRequest;
import com.superprogrammer.device.repository.AnonymousTrialRepository;
import com.superprogrammer.device.repository.AnonymousTrialRepository.AnonymousTrialRecord;
import com.superprogrammer.security.AuthConstants;
import com.superprogrammer.settings.service.SystemSettingService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class AnonymousTrialService {

    private static final List<String> ALL_MODULES = List.of(
            AuthConstants.MODULE_FILES,
            AuthConstants.MODULE_PROCESSES,
            AuthConstants.MODULE_CLIPBOARD,
            AuthConstants.MODULE_WORK_REPORT,
            AuthConstants.MODULE_AI
    );
    private static final Set<String> VALID_MODULES = Set.copyOf(ALL_MODULES);
    private static final int IP_DEVICE_COUNT_WARNING_THRESHOLD = 5;

    private final AnonymousTrialRepository anonymousTrialRepository;
    private final SystemSettingService systemSettingService;
    private final AnonymousTrialRateLimiter rateLimiter;

    public AnonymousTrialStatusDto start(StartTrialRequest request, String clientIp, String userAgent) {
        var existing = anonymousTrialRepository.findByDeviceId(request.deviceId());
        if (existing.isPresent()) {
            return toStatus(requireUsable(existing.get(), request.fingerprintHash()));
        }
        if (!rateLimiter.allowStartByIp(clientIp)) {
            throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS, "该 IP 今日匿名设备注册次数已达上限");
        }
        if (!rateLimiter.allowStartByFingerprint(request.fingerprintHash())) {
            throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS, "该设备今日匿名试用次数已达上限");
        }
        OffsetDateTime now = OffsetDateTime.now();
        String uaHash = hashUserAgent(userAgent);
        AnonymousTrialRecord record = anonymousTrialRepository.insert(
                request.deviceId(),
                request.fingerprintHash(),
                request.deviceName(),
                now,
                now.plusDays(systemSettingService.getAnonymousTrialDays()),
                clientIp,
                uaHash
        );
        return toStatus(record);
    }

    public AnonymousTrialStatusDto status(String deviceId, String fingerprintHash, String clientIp, String userAgent) {
        AnonymousTrialRecord record = anonymousTrialRepository.findByDeviceId(deviceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "匿名试用记录不存在"));
        checkFingerprintConsistency(record, fingerprintHash);
        checkIpUaWarning(record, clientIp, userAgent);
        return toStatus(requireUsable(record, fingerprintHash));
    }

    public AnonymousTrialStatusDto selectFreeModule(SelectFreeModuleRequest request, String clientIp, String userAgent) {
        validateModuleCode(request.freeModuleCode());
        AnonymousTrialRecord record = requireExistingUsable(request.deviceId(), request.fingerprintHash());
        checkIpUaWarning(record, clientIp, userAgent);
        if (isInFullTrial(record)) {
            throw new BusinessException(ErrorCode.UNPROCESSABLE, "试用期内不能选择免费模块");
        }
        if (record.freeModuleCode() != null) {
            throw new BusinessException(ErrorCode.UNPROCESSABLE, "已选择免费模块，请使用更换接口");
        }
        return toStatus(anonymousTrialRepository.updateFreeModule(request.deviceId(), request.freeModuleCode(), OffsetDateTime.now()));
    }

    public AnonymousTrialStatusDto changeFreeModule(SelectFreeModuleRequest request, String clientIp, String userAgent) {
        validateModuleCode(request.freeModuleCode());
        AnonymousTrialRecord record = requireExistingUsable(request.deviceId(), request.fingerprintHash());
        checkIpUaWarning(record, clientIp, userAgent);
        if (isInFullTrial(record)) {
            throw new BusinessException(ErrorCode.UNPROCESSABLE, "试用期内不能更换免费模块");
        }
        if (record.freeModuleCode() == null) {
            throw new BusinessException(ErrorCode.UNPROCESSABLE, "请先选择免费模块");
        }
        OffsetDateTime nextAllowedAt = record.lastFreeModuleChangedAt().plusDays(systemSettingService.getFreeModuleChangeDays());
        if (OffsetDateTime.now().isBefore(nextAllowedAt)) {
            throw new BusinessException(ErrorCode.UNPROCESSABLE, "免费模块每 " + systemSettingService.getFreeModuleChangeDays() + " 天只能更换一次");
        }
        return toStatus(anonymousTrialRepository.updateFreeModule(request.deviceId(), request.freeModuleCode(), OffsetDateTime.now()));
    }

    private AnonymousTrialRecord requireExistingUsable(String deviceId, String fingerprintHash) {
        AnonymousTrialRecord record = anonymousTrialRepository.findByDeviceId(deviceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "匿名试用记录不存在"));
        checkFingerprintConsistency(record, fingerprintHash);
        return requireUsable(record, fingerprintHash);
    }

    private void checkFingerprintConsistency(AnonymousTrialRecord record, String fingerprintHash) {
        if (!record.fingerprintHash().equals(fingerprintHash)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "设备指纹不匹配");
        }
    }

    private void checkIpUaWarning(AnonymousTrialRecord record, String clientIp, String userAgent) {
        // 不阻止正常用户（IP 会变化），但做宽松的一致性检查：
        // 如果同一 deviceId 在短时间内出现在明显不同的 IP/UA 环境，可记录审计日志或触发风控。
        // 当前版本仅做阈值提示，不阻断。
        String uaHash = hashUserAgent(userAgent);
        boolean ipChanged = record.firstSeenIp() != null && !record.firstSeenIp().equals(clientIp);
        boolean uaChanged = record.userAgentHash() != null && !record.userAgentHash().equals(uaHash);
        if (ipChanged || uaChanged) {
            // 未来可接入风控审计；当前放行并记录（可选）。
        }
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

    private String hashUserAgent(String userAgent) {
        if (userAgent == null) {
            return "";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(userAgent.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 一定存在，降级为原字符串截断
            return userAgent.length() > 64 ? userAgent.substring(0, 64) : userAgent;
        }
    }

    public int getIpDeviceCount(String clientIp) {
        if (clientIp == null || clientIp.isBlank()) {
            return 0;
        }
        return anonymousTrialRepository.countByFirstSeenIp(clientIp);
    }

    private void validateModuleCode(String moduleCode) {
        if (!VALID_MODULES.contains(moduleCode)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "模块代码必须是 files、processes、clipboard、work-report 或 ai");
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
