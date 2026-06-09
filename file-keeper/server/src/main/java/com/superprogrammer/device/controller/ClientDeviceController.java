package com.superprogrammer.device.controller;

import com.superprogrammer.common.R;
import com.superprogrammer.device.dto.DeviceDto;
import com.superprogrammer.device.dto.RegisterDeviceRequest;
import com.superprogrammer.device.service.DeviceBindingService;
import com.superprogrammer.security.AuthPrincipal;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/client/devices")
@RequiredArgsConstructor
public class ClientDeviceController {

    private final DeviceBindingService deviceBindingService;

    @PostMapping("/register")
    public R<DeviceDto> register(Authentication authentication, @Valid @RequestBody RegisterDeviceRequest request) {
        AuthPrincipal principal = (AuthPrincipal) authentication.getPrincipal();
        return R.ok(deviceBindingService.registerOrHeartbeat(
                principal.userId(), request.deviceId(), request.fingerprintHash(), request.deviceName()));
    }

    @GetMapping
    public R<List<DeviceDto>> list(Authentication authentication) {
        AuthPrincipal principal = (AuthPrincipal) authentication.getPrincipal();
        return R.ok(deviceBindingService.listByUserId(principal.userId()));
    }
}
