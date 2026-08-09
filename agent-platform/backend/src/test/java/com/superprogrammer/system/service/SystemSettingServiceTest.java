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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SystemSettingServiceTest {

    @Mock
    private SystemSettingMapper mapper;

    @Mock
    private com.superprogrammer.llm.service.AesEncryptService aesEncryptService;

    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();

    private SystemSettingService service;

    @BeforeEach
    void setUp() {
        service = new SystemSettingService(mapper, aesEncryptService, objectMapper);
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

        service.updateAuthSettings(600000L, null);

        verify(mapper).insert(argThat(s ->
                SystemSettingService.ACCESS_TOKEN_EXPIRATION_MS.equals(s.getSettingKey())
                        && "600000".equals(s.getSettingValue())));
    }

    // AC-SEC-FR-008：单点登录开关 upsert + 默认开；传 null = 不改动
    @Test
    void updateAuthSettings_singleSessionFlag_upsertsWhenProvided() {
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(mapper.insert(any(SystemSetting.class))).thenReturn(1);

        service.updateAuthSettings(600000L, false);

        verify(mapper).insert(argThat(s ->
                SystemSettingService.AUTH_SINGLE_SESSION_ENABLED.equals(s.getSettingKey())
                        && "false".equals(s.getSettingValue())));
    }

    @Test
    void getBoolean_singleSession_defaultsTrueWhenMissing() {
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        assertTrue(service.getBoolean(SystemSettingService.AUTH_SINGLE_SESSION_ENABLED, true));
    }

    // AC-SEC-FR-126：L7 阈值/在途上限 upsert + 非法值回退默认
    @Test
    void updateBillingSettings_upsertsBothKeys() {
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(mapper.insert(any(SystemSetting.class))).thenReturn(1);

        service.updateBillingSettings(50L, 2L);

        verify(mapper).insert(argThat(s ->
                SystemSettingService.BILLING_LOW_BALANCE_THRESHOLD.equals(s.getSettingKey())
                        && "50".equals(s.getSettingValue())));
        verify(mapper).insert(argThat(s ->
                SystemSettingService.BILLING_LOW_BALANCE_MAX_INFLIGHT.equals(s.getSettingKey())
                        && "2".equals(s.getSettingValue())));
    }

    @Test
    void getLong_invalidValue_fallsBackToDefault() {
        SystemSetting setting = new SystemSetting();
        setting.setSettingValue("not-a-number");
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(setting);

        assertEquals(100L, service.getLong(SystemSettingService.BILLING_LOW_BALANCE_THRESHOLD, 100L));
    }

    // ============================ V38 LLM_KEY 检索模式 + BOTH 标签语言 ============================

    @Test
    void getMemoryRetrievalMode_shouldAcceptLlmKey() {
        SystemSetting setting = new SystemSetting();
        setting.setSettingValue("LLM_KEY");
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(setting);

        assertEquals("LLM_KEY", service.getMemoryRetrievalMode());
    }

    @Test
    void getMemoryRetrievalMode_shouldDefaultToFullContextWhenUnknown() {
        SystemSetting setting = new SystemSetting();
        setting.setSettingValue("BOGUS");
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(setting);

        assertEquals("LLM_FULL_CONTEXT", service.getMemoryRetrievalMode());
    }

    @Test
    void getMemoryKeyLanguage_shouldAcceptBoth() {
        SystemSetting setting = new SystemSetting();
        setting.setSettingValue("BOTH");
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(setting);

        assertEquals("BOTH", service.getMemoryKeyLanguage());
    }

    // ============================ V38 LLM_KEY 旋钮 ============================

    @Test
    void getLlmKeyCoarseTopN_shouldDefaultTo40WhenMissing() {
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        assertEquals(40, service.getLlmKeyCoarseTopN());
    }

    @Test
    void getLlmKeyCoarseTopN_shouldReturnStoredValue() {
        SystemSetting setting = new SystemSetting();
        setting.setSettingValue("25");
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(setting);

        assertEquals(25, service.getLlmKeyCoarseTopN());
    }

    @Test
    void getLlmKeyCoarseTopN_shouldFallbackOnIllegal() {
        SystemSetting setting = new SystemSetting();
        setting.setSettingValue("abc");
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(setting);

        assertEquals(40, service.getLlmKeyCoarseTopN());
    }

    @Test
    void getLlmKeyRerank_shouldDefaultTrueWhenMissing() {
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        assertTrue(service.getLlmKeyRerank());
    }

    @Test
    void getLlmKeyRerank_shouldReturnStoredFalse() {
        SystemSetting setting = new SystemSetting();
        setting.setSettingValue("false");
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(setting);

        assertFalse(service.getLlmKeyRerank());
    }

    @Test
    void getKeywordMax_shouldDefaultTo8WhenMissing() {
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        assertEquals(8, service.getKeywordMax());
    }

    @Test
    void getKeywordMax_shouldReturnStoredZeroAsUnlimited() {
        SystemSetting setting = new SystemSetting();
        setting.setSettingValue("0");
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(setting);

        assertEquals(0, service.getKeywordMax());
    }

    @Test
    void getKeywordMax_shouldReturnStoredValue() {
        SystemSetting setting = new SystemSetting();
        setting.setSettingValue("12");
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(setting);

        assertEquals(12, service.getKeywordMax());
    }

    // ============================ 记忆管线 LLM 默认 model（V76）============================

    @Test
    void getMemoryJudgeModel_shouldReturnStoredValue() {
        SystemSetting setting = new SystemSetting();
        setting.setSettingValue("glm-4.5");
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(setting);

        assertEquals("glm-4.5", service.getMemoryJudgeModel());
    }

    @Test
    void getMemoryJudgeModel_shouldFallbackToDefaultWhenMissing() {
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        // 缺失 → 回退 RagConfig.MEMORY_JUDGE_MODEL 常量（doubao-seed-2.0-code）
        assertEquals(com.superprogrammer.knowledge.service.RagConfig.MEMORY_JUDGE_MODEL,
                service.getMemoryJudgeModel());
    }

    @Test
    void getMemoryJudgeModel_shouldFallbackOnBlank() {
        SystemSetting setting = new SystemSetting();
        setting.setSettingValue("   ");
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(setting);

        assertEquals(com.superprogrammer.knowledge.service.RagConfig.MEMORY_JUDGE_MODEL,
                service.getMemoryJudgeModel());
    }
}
