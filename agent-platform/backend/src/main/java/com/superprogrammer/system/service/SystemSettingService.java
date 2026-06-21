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
    /** RAG/记忆模式总开关（false=opt-in）。4 层优先级：session>agent/workflow>global。 */
    public static final String RAG_MEMORY_ENABLED = "rag.memory.enabled";

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

    // ============================ 通用 boolean get/set ============================

    /** 通用读 boolean（值存 TEXT 'true'/'false'）；缺失/非法 → def。 */
    public boolean getBoolean(String key, boolean def) {
        String value = getValue(key);
        if (value == null || value.isBlank()) {
            return def;
        }
        return Boolean.parseBoolean(value.trim());
    }

    /** 通用写 boolean。 */
    public void setBoolean(String key, boolean val, String description) {
        upsert(key, String.valueOf(val), description);
    }

    // ============================ RAG/记忆模式 ============================

    /** RAG/记忆模式全局总开关，默认 false（opt-in）。 */
    public boolean getRagMemoryEnabled() {
        return getBoolean(RAG_MEMORY_ENABLED, false);
    }

    public void updateRagMemoryEnabled(boolean enabled) {
        setBoolean(RAG_MEMORY_ENABLED, enabled, "RAG/记忆模式总开关（false=opt-in）");
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
