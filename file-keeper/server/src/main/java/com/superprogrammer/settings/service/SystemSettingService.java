package com.superprogrammer.settings.service;

import com.superprogrammer.settings.SettingKeys;
import com.superprogrammer.settings.dto.SystemSettingsBundle;
import com.superprogrammer.settings.repository.SystemSettingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SystemSettingService {

    private final SystemSettingRepository settingRepository;

    public int getInt(String key, int defaultValue) {
        return settingRepository.findValue(key)
                .map(value -> {
                    try {
                        return Integer.parseInt(value.trim());
                    } catch (NumberFormatException e) {
                        return defaultValue;
                    }
                })
                .orElse(defaultValue);
    }

    public int getDefaultDeviceLimit() {
        return getInt(SettingKeys.DEFAULT_DEVICE_LIMIT, SettingKeys.DEFAULT_DEVICE_LIMIT_VALUE);
    }

    public int getDefaultOfflineCacheMinutes() {
        return getInt(SettingKeys.DEFAULT_OFFLINE_CACHE_MINUTES, SettingKeys.DEFAULT_OFFLINE_CACHE_MINUTES_VALUE);
    }

    public int getAnonymousTrialDays() {
        return getInt(SettingKeys.ANONYMOUS_TRIAL_DAYS, SettingKeys.DEFAULT_ANONYMOUS_TRIAL_DAYS_VALUE);
    }

    public int getFreeModuleChangeDays() {
        return getInt(SettingKeys.FREE_MODULE_CHANGE_DAYS, SettingKeys.DEFAULT_FREE_MODULE_CHANGE_DAYS_VALUE);
    }

    public SystemSettingsBundle loadBundle() {
        return new SystemSettingsBundle(
                getDefaultDeviceLimit(),
                getDefaultOfflineCacheMinutes(),
                getAnonymousTrialDays(),
                getFreeModuleChangeDays()
        );
    }

    public SystemSettingsBundle updateBundle(SystemSettingsBundle bundle, Long adminUserId) {
        upsert(SettingKeys.DEFAULT_DEVICE_LIMIT, String.valueOf(bundle.defaultDeviceLimit()),
                SettingKeys.DESCRIPTION_DEFAULT_DEVICE_LIMIT, adminUserId);
        upsert(SettingKeys.DEFAULT_OFFLINE_CACHE_MINUTES, String.valueOf(bundle.defaultOfflineCacheMinutes()),
                SettingKeys.DESCRIPTION_DEFAULT_OFFLINE_CACHE_MINUTES, adminUserId);
        upsert(SettingKeys.ANONYMOUS_TRIAL_DAYS, String.valueOf(bundle.anonymousTrialDays()),
                SettingKeys.DESCRIPTION_ANONYMOUS_TRIAL_DAYS, adminUserId);
        upsert(SettingKeys.FREE_MODULE_CHANGE_DAYS, String.valueOf(bundle.freeModuleChangeDays()),
                SettingKeys.DESCRIPTION_FREE_MODULE_CHANGE_DAYS, adminUserId);
        return loadBundle();
    }

    private void upsert(String key, String value, String description, Long adminUserId) {
        settingRepository.upsert(key, value, description, adminUserId);
    }
}
