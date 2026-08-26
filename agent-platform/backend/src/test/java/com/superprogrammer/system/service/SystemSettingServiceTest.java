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
import static org.junit.jupiter.api.Assertions.assertThrows;
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

        service.updateBillingSettings(50L, 2L, null);

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

    // ---- D8（V160）：闲时时段配置 ----

    @Test
    void getOffPeakSchedule_missing_returnsDisabledDefault() {
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        var vo = service.getOffPeakSchedule();
        assertFalse(vo.getEnabled());
        assertTrue(vo.getWeekday().isEmpty());
        assertTrue(vo.getWeekend().isEmpty());
    }

    @Test
    void getOffPeakSchedule_corruptJson_returnsDisabledDefault() {
        SystemSetting setting = new SystemSetting();
        setting.setSettingKey(SystemSettingService.BILLING_OFF_PEAK_SCHEDULE);
        setting.setSettingValue("{not-json");
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(setting);

        assertFalse(service.getOffPeakSchedule().getEnabled());
    }

    @Test
    void updateOffPeakSchedule_valid_roundtrips() {
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(mapper.insert(any(SystemSetting.class))).thenReturn(1);
        var captor = org.mockito.ArgumentCaptor.forClass(SystemSetting.class);

        service.updateOffPeakSchedule(
                com.superprogrammer.system.dto.OffPeakScheduleVO.builder()
                        .enabled(true)
                        .weekday(java.util.List.of(com.superprogrammer.system.dto.OffPeakWindowVO.builder()
                                .start("22:00").end("08:00").build()))
                        .weekend(java.util.List.of(com.superprogrammer.system.dto.OffPeakWindowVO.builder()
                                .start("00:00").end("24:00").build()))
                        .build());

        verify(mapper).insert(captor.capture());
        String json = captor.getValue().getSettingValue();
        assertTrue(json.contains("\"enabled\":true"));
        assertTrue(json.contains("22:00"));

        // 读回：拿落库 JSON 反查，应还原为归一化 VO
        SystemSetting row = new SystemSetting();
        row.setSettingKey(SystemSettingService.BILLING_OFF_PEAK_SCHEDULE);
        row.setSettingValue(json);
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(row);
        var back = service.getOffPeakSchedule();
        assertTrue(back.getEnabled());
        assertEquals("Asia/Shanghai", back.getTimezone());
        assertEquals(1, back.getWeekday().size());
        assertEquals("22:00", back.getWeekday().get(0).getStart());
        assertEquals("24:00", back.getWeekend().get(0).getEnd());
    }

    @Test
    void updateOffPeakSchedule_badTimeFormat_throws() {
        var vo = com.superprogrammer.system.dto.OffPeakScheduleVO.builder()
                .enabled(true)
                .weekday(java.util.List.of(com.superprogrammer.system.dto.OffPeakWindowVO.builder()
                        .start("25:00").end("08:00").build()))
                .build();
        assertThrows(com.superprogrammer.common.exception.BusinessException.class,
                () -> service.updateOffPeakSchedule(vo));
    }

    @Test
    void updateOffPeakSchedule_overFourWindows_throws() {
        java.util.List<com.superprogrammer.system.dto.OffPeakWindowVO> windows = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            windows.add(com.superprogrammer.system.dto.OffPeakWindowVO.builder()
                    .start("0" + i + ":00").end("0" + (i + 1) + ":00").build());
        }
        var vo = com.superprogrammer.system.dto.OffPeakScheduleVO.builder()
                .enabled(true).weekday(windows).build();
        assertThrows(com.superprogrammer.common.exception.BusinessException.class,
                () -> service.updateOffPeakSchedule(vo));
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
    void getMemoryJudgeModel_shouldUseGlobalChatDefaultWhenSpecificSettingMissing() {
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        assertEquals(null, service.getMemoryJudgeModel());
    }

    @Test
    void getMemoryJudgeModel_shouldFallbackOnBlank() {
        SystemSetting setting = new SystemSetting();
        setting.setSettingValue("   ");
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(setting);

        assertEquals(null, service.getMemoryJudgeModel());
    }

    @Test
    void globalDefaultModels_shouldBeNullableWhenAdminHasNotConfiguredThem() {
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        assertEquals(null, service.getDefaultChatModel());
        assertEquals(null, service.getDefaultEmbeddingModel());
    }

    // ============================ V77 记忆标签大类词表 ============================

    @Test
    void getMemoryTagVocab_shouldReturnStoredArray() {
        SystemSetting setting = new SystemSetting();
        setting.setSettingValue("[\"旅行出行\",\"技术技能\"]");
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(setting);

        assertEquals(java.util.List.of("旅行出行", "技术技能"), service.getMemoryTagVocab());
    }

    @Test
    void getMemoryTagVocab_shouldFallbackToDefault13WhenMissing() {
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        java.util.List<String> v = service.getMemoryTagVocab();
        assertEquals(com.superprogrammer.knowledge.service.RagConfig.MEMORY_TAG_VOCAB_DEFAULT, v);
        assertEquals(13, v.size(), "内置大类词表 13 类");
    }

    @Test
    void getMemoryTagVocab_shouldFallbackOnIllegalJson() {
        SystemSetting setting = new SystemSetting();
        setting.setSettingValue("不是JSON");
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(setting);

        assertEquals(com.superprogrammer.knowledge.service.RagConfig.MEMORY_TAG_VOCAB_DEFAULT,
                service.getMemoryTagVocab());
    }

    @Test
    void getMemoryTagVocab_shouldFallbackOnEmptyArray() {
        SystemSetting setting = new SystemSetting();
        setting.setSettingValue("[]");
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(setting);

        assertEquals(com.superprogrammer.knowledge.service.RagConfig.MEMORY_TAG_VOCAB_DEFAULT,
                service.getMemoryTagVocab());
    }

    // ============================ 安全体系 S4 · 上传解析防护默认值（SEC-FR-031/032） ============================

    @Test
    void getUploadMagicSniffEnabled_shouldDefaultTrueWhenMissing() {
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        assertTrue(service.getUploadMagicSniffEnabled());
    }

    @Test
    void getUploadMaxPixels_shouldDefaultHundredMillionWhenMissing() {
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        assertEquals(100_000_000L, service.getUploadMaxPixels());
    }

    @Test
    void getUploadMaxPixels_shouldReturnStoredValue() {
        SystemSetting setting = new SystemSetting();
        setting.setSettingValue("5000000");
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(setting);

        assertEquals(5_000_000L, service.getUploadMaxPixels());
    }

    @Test
    void getUploadMaxPixels_shouldFallbackOnZeroOrIllegal() {
        SystemSetting zero = new SystemSetting();
        zero.setSettingValue("0");
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(zero);
        assertEquals(100_000_000L, service.getUploadMaxPixels());

        SystemSetting bad = new SystemSetting();
        bad.setSettingValue("abc");
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(bad);
        assertEquals(100_000_000L, service.getUploadMaxPixels());
    }

    @Test
    void getUploadMaxParseChars_shouldDefaultHundredThousandWhenMissing() {
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        assertEquals(100_000, service.getUploadMaxParseChars());
    }

    @Test
    void getUploadMaxParseChars_shouldReturnStoredValue() {
        SystemSetting setting = new SystemSetting();
        setting.setSettingValue("20000");
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(setting);

        assertEquals(20_000, service.getUploadMaxParseChars());
    }

    @Test
    void getUserStorageQuotaMb_shouldDefault2048WhenMissing() {
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        assertEquals(2048L, service.getUserStorageQuotaMb());
    }

    @Test
    void getUserStorageQuotaMb_shouldReturnStoredZeroAsDisabled() {
        SystemSetting setting = new SystemSetting();
        setting.setSettingValue("0");
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(setting);

        assertEquals(0L, service.getUserStorageQuotaMb());
    }
}
