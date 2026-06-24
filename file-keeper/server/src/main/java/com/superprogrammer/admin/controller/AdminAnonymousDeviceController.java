package com.superprogrammer.admin.controller;

import com.superprogrammer.admin.service.AdminAnonymousDeviceService;
import com.superprogrammer.common.PageResult;
import com.superprogrammer.common.R;
import com.superprogrammer.device.dto.AnonymousDeviceDto;
import com.superprogrammer.device.repository.AnonymousDeviceAdminRepository;
import com.superprogrammer.security.AuthPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/anonymous-devices")
@RequiredArgsConstructor
public class AdminAnonymousDeviceController {

    private final AdminAnonymousDeviceService adminAnonymousDeviceService;
    private final AnonymousDeviceAdminRepository anonymousDeviceAdminRepository;

    @GetMapping
    public R<PageResult<AnonymousDeviceDto>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long minResetCount,
            @RequestParam(required = false) String firstSeenIp,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size
    ) {
        return R.ok(adminAnonymousDeviceService.list(status, minResetCount, firstSeenIp, page, size));
    }

    @GetMapping("/ip-abuse")
    public R<List<AnonymousDeviceAdminRepository.IpDeviceCount>> ipAbuse(
            @RequestParam(defaultValue = "5") int minCount
    ) {
        return R.ok(anonymousDeviceAdminRepository.countDevicesByIp(minCount));
    }

    @PostMapping("/{deviceId}/reset-trial")
    public R<AnonymousDeviceDto> resetTrial(Authentication authentication, @PathVariable String deviceId) {
        AuthPrincipal principal = (AuthPrincipal) authentication.getPrincipal();
        return R.ok(adminAnonymousDeviceService.resetTrial(principal.userId(), deviceId));
    }

    @PostMapping("/{deviceId}/disable")
    public R<AnonymousDeviceDto> disable(Authentication authentication, @PathVariable String deviceId) {
        AuthPrincipal principal = (AuthPrincipal) authentication.getPrincipal();
        return R.ok(adminAnonymousDeviceService.disable(principal.userId(), deviceId));
    }

    @PostMapping("/{deviceId}/enable")
    public R<AnonymousDeviceDto> enable(Authentication authentication, @PathVariable String deviceId) {
        AuthPrincipal principal = (AuthPrincipal) authentication.getPrincipal();
        return R.ok(adminAnonymousDeviceService.enable(principal.userId(), deviceId));
    }
}
