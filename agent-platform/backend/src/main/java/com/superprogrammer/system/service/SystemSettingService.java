package com.superprogrammer.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.superprogrammer.system.dto.AuthSettingsVO;
import com.superprogrammer.system.entity.SystemSetting;
import com.superprogrammer.system.mapper.SystemSettingMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;

@Service
@RequiredArgsConstructor
public class SystemSettingService {
    public static final String ACCESS_TOKEN_EXPIRATION_MS = "auth.access_token_expiration_ms";

    private final SystemSettingMapper mapper;

    @Value("${jwt.access-expiration:900000}")
    private Long defaultAccessExpirationMs;

    public long getAccessTokenExpirationMs() {
        String value = getValue(ACCESS_TOKEN_EXPIRATION_MS);
        if (value == null || value.isBlank()) {
            return defaultAccessExpirationMs;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return defaultAccessExpirationMs;
        }
    }

    public AuthSettingsVO getAuthSettings() {
        return AuthSettingsVO.builder()
                .accessTokenExpirationMs(getAccessTokenExpirationMs())
                .build();
    }

    public AuthSettingsVO updateAuthSettings(long accessTokenExpirationMs) {
        upsert(ACCESS_TOKEN_EXPIRATION_MS, String.valueOf(accessTokenExpirationMs), "Access Token有效期(毫秒)");
        return getAuthSettings();
    }

    private String getValue(String key) {
        SystemSetting setting = mapper.selectOne(new LambdaQueryWrapper<SystemSetting>()
                .eq(SystemSetting::getSettingKey, key));
        return setting != null ? setting.getSettingValue() : null;
    }

    private void upsert(String key, String value, String description) {
        SystemSetting setting = mapper.selectOne(new LambdaQueryWrapper<SystemSetting>()
                .eq(SystemSetting::getSettingKey, key));
        OffsetDateTime now = OffsetDateTime.now();
        if (setting == null) {
            setting = new SystemSetting();
            setting.setSettingKey(key);
            setting.setSettingValue(value);
            setting.setDescription(description);
            setting.setCreatedAt(now);
            setting.setUpdatedAt(now);
            mapper.insert(setting);
        } else {
            setting.setSettingValue(value);
            setting.setDescription(description);
            setting.setUpdatedAt(now);
            mapper.updateById(setting);
        }
    }
}
