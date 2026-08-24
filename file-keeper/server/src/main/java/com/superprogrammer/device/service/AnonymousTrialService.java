package com.superprogrammer.device.service;

import com.superprogrammer.common.BusinessException;
import com.superprogrammer.common.ErrorCode;
import com.superprogrammer.device.dto.AnonymousTrialStatusDto;
import com.superprogrammer.device.dto.SelectFreeModuleRequest;
import com.superprogrammer.device.dto.StartTrialRequest;
import com.superprogrammer.security.AuthConstants;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AnonymousTrialService {

    private static final List<String> LOCAL_MODULES = List.of(
            AuthConstants.MODULE_FILES,
            AuthConstants.MODULE_PROCESSES,
            AuthConstants.MODULE_CLIPBOARD
    );

    public AnonymousTrialStatusDto start(StartTrialRequest request, String clientIp, String userAgent) {
        return compatibilityStatus(request.deviceId(), request.deviceName());
    }

    public AnonymousTrialStatusDto status(String deviceId, String fingerprintHash, String clientIp, String userAgent) {
        return compatibilityStatus(deviceId, null);
    }

    public AnonymousTrialStatusDto selectFreeModule(SelectFreeModuleRequest request, String clientIp, String userAgent) {
        throw deprecatedFreeModuleSelection();
    }

    public AnonymousTrialStatusDto changeFreeModule(SelectFreeModuleRequest request, String clientIp, String userAgent) {
        throw deprecatedFreeModuleSelection();
    }

    public int getIpDeviceCount(String clientIp) {
        return 0;
    }

    private AnonymousTrialStatusDto compatibilityStatus(String deviceId, String deviceName) {
        return new AnonymousTrialStatusDto(
                deviceId,
                deviceName,
                null,
                null,
                false,
                true,
                null,
                null,
                null,
                LOCAL_MODULES
        );
    }

    private BusinessException deprecatedFreeModuleSelection() {
        return new BusinessException(
                ErrorCode.UNPROCESSABLE,
                "免费模块选择已废弃，本地模块无需登录即可使用"
        );
    }
}
