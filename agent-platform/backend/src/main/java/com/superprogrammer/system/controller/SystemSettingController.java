package com.superprogrammer.system.controller;

import com.superprogrammer.auth.security.RequirePermission;
import com.superprogrammer.common.result.R;
import com.superprogrammer.system.dto.AuthSettingsUpdateRequest;
import com.superprogrammer.system.dto.AuthSettingsVO;
import com.superprogrammer.system.service.SystemSettingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/system/settings")
@RequiredArgsConstructor
public class SystemSettingController {
    private final SystemSettingService service;

    @GetMapping("/auth")
    @RequirePermission("role:manage")
    public ResponseEntity<R<AuthSettingsVO>> getAuthSettings() {
        return ResponseEntity.ok(R.ok(service.getAuthSettings()));
    }

    @PutMapping("/auth")
    @RequirePermission("role:manage")
    public ResponseEntity<R<AuthSettingsVO>> updateAuthSettings(
            @Valid @RequestBody AuthSettingsUpdateRequest request) {
        return ResponseEntity.ok(R.ok(service.updateAuthSettings(request.getAccessTokenExpirationMs())));
    }
}
