package com.superprogrammer.admin.service;

import com.superprogrammer.audit.service.AdminAuditLogService;
import com.superprogrammer.common.BusinessException;
import com.superprogrammer.common.ErrorCode;
import com.superprogrammer.common.PageResult;
import com.superprogrammer.device.dto.AnonymousDeviceDto;
import com.superprogrammer.device.repository.AnonymousDeviceAdminRepository;
import com.superprogrammer.security.AuthConstants;
import com.superprogrammer.settings.service.SystemSettingService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

@Service
@RequiredArgsConstructor
public class AdminAnonymousDeviceService {

    private final AnonymousDeviceAdminRepository anonymousDeviceAdminRepository;
    private final SystemSettingService systemSettingService;
    private final AdminAuditLogService auditLogService;

    public PageResult<AnonymousDeviceDto> list(String status, Long minResetCount, String firstSeenIp, long page, long size) {
        return anonymousDeviceAdminRepository.findAll(status, minResetCount, firstSeenIp, page, size);
    }

    private static final int MAX_TRIAL_RESET_COUNT = 3;

    @Transactional
    public AnonymousDeviceDto resetTrial(Long adminUserId, String deviceId) {
        AnonymousDeviceDto device = requireByDeviceId(deviceId);
        if (device.trialResetCount() >= MAX_TRIAL_RESET_COUNT) {
            throw new BusinessException(ErrorCode.UNPROCESSABLE, "该设备试用重置次数已达上限");
        }
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime newExpiresAt = now.plusDays(systemSettingService.getAnonymousTrialDays());
        int updated = anonymousDeviceAdminRepository.resetTrial(deviceId, newExpiresAt, adminUserId);
        if (updated == 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "匿名设备不存在");
        }
        auditLogService.record(adminUserId, "anonymous_device.reset_trial", "anonymous_device", deviceId,
                "trialExpiresAt=" + newExpiresAt + ", resetCount=" + (device.trialResetCount() + 1));
        return anonymousDeviceAdminRepository.findByDeviceId(deviceId);
    }

    @Transactional
    public AnonymousDeviceDto disable(Long adminUserId, String deviceId) {
        return updateStatus(adminUserId, deviceId, AuthConstants.STATUS_DISABLED, "anonymous_device.disable");
    }

    @Transactional
    public AnonymousDeviceDto enable(Long adminUserId, String deviceId) {
        return updateStatus(adminUserId, deviceId, AuthConstants.STATUS_ACTIVE, "anonymous_device.enable");
    }

    private AnonymousDeviceDto updateStatus(Long adminUserId, String deviceId, String status, String action) {
        AnonymousDeviceDto device = requireByDeviceId(deviceId);
        if (status.equals(device.status())) {
            throw new BusinessException(ErrorCode.UNPROCESSABLE, "设备已经是该状态");
        }
        int updated = anonymousDeviceAdminRepository.updateStatus(deviceId, status, adminUserId);
        if (updated == 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "匿名设备不存在");
        }
        auditLogService.record(adminUserId, action, "anonymous_device", deviceId, "status=" + status);
        return anonymousDeviceAdminRepository.findByDeviceId(deviceId);
    }

    private AnonymousDeviceDto requireByDeviceId(String deviceId) {
        AnonymousDeviceDto device = anonymousDeviceAdminRepository.findByDeviceId(deviceId);
        if (device == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "匿名设备不存在");
        }
        return device;
    }
}
