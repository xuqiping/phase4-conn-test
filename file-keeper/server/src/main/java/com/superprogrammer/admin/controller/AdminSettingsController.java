package com.superprogrammer.admin.controller;

import com.superprogrammer.common.BusinessException;
import com.superprogrammer.common.ErrorCode;
import com.superprogrammer.common.R;
import com.superprogrammer.settings.SettingKeys;
import com.superprogrammer.settings.dto.SystemSettingsBundle;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/settings")
@Deprecated(forRemoval = true)
public class AdminSettingsController {

    @GetMapping
    public R<SystemSettingsBundle> get() {
        return R.ok(new SystemSettingsBundle(
                SettingKeys.DEFAULT_DEVICE_LIMIT_VALUE,
                SettingKeys.DEFAULT_OFFLINE_CACHE_MINUTES_VALUE,
                SettingKeys.DEFAULT_ANONYMOUS_TRIAL_DAYS_VALUE,
                SettingKeys.DEFAULT_FREE_MODULE_CHANGE_DAYS_VALUE
        ));
    }

    @PutMapping
    public R<SystemSettingsBundle> update(Authentication authentication, @Valid @RequestBody SystemSettingsBundle bundle) {
        throw new BusinessException(ErrorCode.UNPROCESSABLE, "商业化系统设置已废弃");
    }
}
