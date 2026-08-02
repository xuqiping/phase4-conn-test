package com.superprogrammer.workreport;

import com.superprogrammer.ai.service.AiConfigService;
import com.superprogrammer.workreport.dto.FixedWorkCompletionStats;
import com.superprogrammer.workreport.entity.FixedWorkItem;
import com.superprogrammer.workreport.entity.InboundMessage;
import com.superprogrammer.workreport.entity.InspirationNote;
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

    @Test
    void enhancedPromptContainsCompletionStatsAndInspirations() {
        WorkLog log = new WorkLog();
        log.setContent("工作记录");
        log.setLogDate(LocalDate.now());

        FixedWorkCompletionStats stats = new FixedWorkCompletionStats(
                0.5,
                List.of(new FixedWorkCompletionStats.ItemCompletionRate(1L, "晨会", 0.5, 2, 1)),
                List.of(new FixedWorkCompletionStats.MissLogEntry(LocalDate.now().minusDays(1), "晨会")),
                1
        );

        InspirationNote note = new InspirationNote();
        note.setContent("新想法");

        when(aiConfigService.getEffectiveConfig(1L, null)).thenReturn(null);

        String result = aiSummaryService.summarize(
                new AiSummaryService.AiSummaryContext(List.of(log), List.of(), "WEEKLY", stats, List.of(), List.of(note)),
                null, 1L
        );

        assertTrue(result.contains("工作记录"));
        assertTrue(result.contains("新想法"));
        assertTrue(result.contains("晨会"));
    }

    @Test
    void normalizesDoubaoCodingBaseUrl() {
        assertEquals(
                "https://ark.cn-beijing.volces.com/api/coding/v3/chat/completions",
                aiSummaryService.normalizeOpenAiEndpoint("https://ark.cn-beijing.volces.com/api/coding")
        );
    }

    @Test
    void keepsFullChatCompletionsUrlUnchanged() {
        assertEquals(
                "https://ark.cn-beijing.volces.com/api/coding/v3/chat/completions",
                aiSummaryService.normalizeOpenAiEndpoint("https://ark.cn-beijing.volces.com/api/coding/v3/chat/completions")
        );
    }

    @Test
    void appendsChatCompletionsToBaseUrl() {
        assertEquals(
                "https://ark.cn-beijing.volces.com/api/v3/chat/completions",
                aiSummaryService.normalizeOpenAiEndpoint("https://ark.cn-beijing.volces.com/api/v3")
        );
    }

    @Test
    void trimsTrailingSlashesBeforeNormalizing() {
        assertEquals(
                "https://api.openai.com/v1/chat/completions",
                aiSummaryService.normalizeOpenAiEndpoint("https://api.openai.com/v1//")
        );
    }
}
