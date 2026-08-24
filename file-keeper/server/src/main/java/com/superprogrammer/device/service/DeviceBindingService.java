package com.superprogrammer.device.service;

import com.superprogrammer.audit.service.AdminAuditLogService;
import com.superprogrammer.common.BusinessException;
import com.superprogrammer.common.ErrorCode;
import com.superprogrammer.device.dto.DeviceDto;
import com.superprogrammer.device.repository.DeviceRepository;
import com.superprogrammer.user.entity.User;
import com.superprogrammer.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class DeviceBindingService {

    private final DeviceRepository deviceRepository;
    private final UserRepository userRepository;
    private final AdminAuditLogService auditLogService;

    public DeviceDto registerOrHeartbeat(Long userId, String deviceId, String fingerprintHash, String deviceName) {
        User user = userRepository.requireById(userId);
        if ("disabled".equals(user.getStatus())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "账号已禁用");
        }
        // Check if already bound
        var existing = deviceRepository.findByUserIdAndDeviceId(userId, deviceId);
        if (existing.isPresent()) {
            DeviceDto activeDevice = requireActiveDevice(userId, deviceId);
            deviceRepository.updateLastSeenAt(activeDevice.id());
            return deviceRepository.findByUserIdAndDeviceId(userId, deviceId).orElseThrow();
        }
        return deviceRepository.insert(userId, deviceId, fingerprintHash, deviceName);
    }

    public DeviceDto requireActiveDevice(Long userId, String deviceId) {
        User user = userRepository.requireById(userId);
        if (!"active".equals(user.getStatus())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "账号不可用");
        }
        DeviceDto device = deviceRepository.findByUserIdAndDeviceId(userId, deviceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.FORBIDDEN, "设备不可用"));
        if (!"active".equals(device.status())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "设备已禁用");
        }
        return device;
    }

    public List<DeviceDto> listByUserId(Long userId) {
        return deviceRepository.findByUserId(userId);
    }

    public void disableDevice(Long adminUserId, Long targetUserId, String deviceId) {
        DeviceDto device = deviceRepository.findByUserIdAndDeviceId(targetUserId, deviceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "设备不存在"));
        deviceRepository.updateStatus(device.id(), "disabled", adminUserId);
        auditLogService.record(adminUserId, "device.disable", "device", deviceId,
                "禁用设备 " + deviceId);
    }
}
