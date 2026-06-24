package com.superprogrammer.admin.controller;

import com.superprogrammer.audit.service.AdminAuditLogService;
import com.superprogrammer.common.R;
import com.superprogrammer.security.AuthPrincipal;
import com.superprogrammer.settings.dto.SystemSettingsBundle;
import com.superprogrammer.settings.service.SystemSettingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/settings")
@RequiredArgsConstructor
public class AdminSettingsController {

    private final SystemSettingService systemSettingService;
    private final AdminAuditLogService auditLogService;

    @GetMapping
    public R<SystemSettingsBundle> get() {
        return R.ok(systemSettingService.loadBundle());
    }

    @PutMapping
    public R<SystemSettingsBundle> update(Authentication authentication, @Valid @RequestBody SystemSettingsBundle bundle) {
        AuthPrincipal principal = (AuthPrincipal) authentication.getPrincipal();
        SystemSettingsBundle updated = systemSettingService.updateBundle(bundle, principal.userId());
        auditLogService.record(principal.userId(), "system.update_settings", "system_setting", null,
                "defaultDeviceLimit=" + updated.defaultDeviceLimit()
                        + ", defaultOfflineCacheMinutes=" + updated.defaultOfflineCacheMinutes()
                        + ", anonymousTrialDays=" + updated.anonymousTrialDays()
                        + ", freeModuleChangeDays=" + updated.freeModuleChangeDays());
        return R.ok(updated);
    }
}
