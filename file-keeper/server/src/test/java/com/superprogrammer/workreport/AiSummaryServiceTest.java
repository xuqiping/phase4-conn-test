package com.superprogrammer.workreport;

import com.superprogrammer.ai.service.AiConfigService;
import com.superprogrammer.workreport.entity.FixedWorkItem;
import com.superprogrammer.workreport.entity.WorkLog;
import com.superprogrammer.workreport.service.AiSummaryService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiSummaryServiceTest {

    private final RestTemplateBuilder restTemplateBuilder = mock(RestTemplateBuilder.class);
    private final AiConfigService aiConfigService = mock(AiConfigService.class);
    private final AiSummaryService aiSummaryService = new AiSummaryService(restTemplateBuilder, aiConfigService);

    AiSummaryServiceTest() {
        when(restTemplateBuilder.setConnectTimeout(any(Duration.class))).thenReturn(restTemplateBuilder);
        when(restTemplateBuilder.setReadTimeout(any(Duration.class))).thenReturn(restTemplateBuilder);
    }

    @Test
    void returnsEmptyWhenNoData() {
        String result = aiSummaryService.summarize(List.of(), List.of(), "DAILY", null, 1L);
        assertEquals("", result);
    }

    @Test
    void fallsBackWhenNoAiConfig() {
        WorkLog log = new WorkLog();
        log.setContent("完成接口开发");
        log.setLogDate(LocalDate.of(2026, 6, 21));

        FixedWorkItem item = new FixedWorkItem();
        item.setContent("编写测试");
        item.setRecurrenceType("DAILY");
        item.setReminderTime(LocalTime.of(9, 0));

        when(aiConfigService.getEffectiveConfig(1L, null)).thenReturn(null);

        String result = aiSummaryService.summarize(List.of(log), List.of(item), "DAILY", null, 1L);

        assertNotNull(result);
        assertTrue(result.contains("完成接口开发"));
        assertTrue(result.contains("编写测试"));
    }

    @Test
    void dailyPromptContainsContent() {
        WorkLog log = new WorkLog();
        log.setContent("工作记录");
        log.setLogDate(LocalDate.now());

        when(aiConfigService.getEffectiveConfig(1L, null)).thenReturn(null);

        String result = aiSummaryService.summarize(List.of(log), List.of(), "DAILY", null, 1L);
        assertFalse(result.isEmpty());
    }
}
