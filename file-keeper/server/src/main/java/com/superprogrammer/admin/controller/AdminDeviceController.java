package com.superprogrammer.admin.controller;

import com.superprogrammer.admin.dto.UserReviewRequest;
import com.superprogrammer.common.R;
import com.superprogrammer.device.dto.DeviceDto;
import com.superprogrammer.device.service.DeviceBindingService;
import com.superprogrammer.security.AuthPrincipal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/users/{userId}/devices")
@RequiredArgsConstructor
public class AdminDeviceController {

    private final DeviceBindingService deviceBindingService;

    @GetMapping
    public R<List<DeviceDto>> list(@PathVariable Long userId) {
        return R.ok(deviceBindingService.listByUserId(userId));
    }

    @PostMapping("/{deviceId}/disable")
    public R<Void> disable(Authentication authentication, @PathVariable Long userId,
                            @PathVariable String deviceId, @RequestBody UserReviewRequest request) {
        AuthPrincipal principal = (AuthPrincipal) authentication.getPrincipal();
        deviceBindingService.disableDevice(principal.userId(), userId, deviceId);
        return R.ok();
    }
}
