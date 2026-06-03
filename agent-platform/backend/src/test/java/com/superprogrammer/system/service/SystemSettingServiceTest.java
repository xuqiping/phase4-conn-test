package com.superprogrammer.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.superprogrammer.system.entity.SystemSetting;
import com.superprogrammer.system.mapper.SystemSettingMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SystemSettingServiceTest {

    @Mock
    private SystemSettingMapper mapper;

    private SystemSettingService service;

    @BeforeEach
    void setUp() {
        service = new SystemSettingService(mapper);
        ReflectionTestUtils.setField(service, "defaultAccessExpirationMs", 900000L);
    }

    @Test
    void getAccessTokenExpirationMs_shouldReturnStoredValue() {
        SystemSetting setting = new SystemSetting();
        setting.setSettingKey(SystemSettingService.ACCESS_TOKEN_EXPIRATION_MS);
        setting.setSettingValue("300000");
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(setting);

        assertEquals(300000L, service.getAccessTokenExpirationMs());
    }

    @Test
    void getAccessTokenExpirationMs_shouldFallbackToDefaultWhenMissing() {
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        assertEquals(900000L, service.getAccessTokenExpirationMs());
    }

    @Test
    void updateAuthSettings_shouldUpsertValue() {
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(mapper.insert(any(SystemSetting.class))).thenReturn(1);

        service.updateAuthSettings(600000L);

        verify(mapper).insert(argThat(s ->
                SystemSettingService.ACCESS_TOKEN_EXPIRATION_MS.equals(s.getSettingKey())
                        && "600000".equals(s.getSettingValue())));
    }
}
