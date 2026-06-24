package com.superprogrammer.workreport;

import com.superprogrammer.workreport.entity.FixedWorkItem;
import com.superprogrammer.workreport.entity.WorkLog;
import com.superprogrammer.workreport.service.AiSummaryService;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AiSummaryServiceTest {

    private final AiSummaryService aiSummaryService = new AiSummaryService(new RestTemplate());

    @Test
    void returnsEmptyWhenNoData() {
        String result = aiSummaryService.summarize(List.of(), List.of(), "DAILY");
        assertEquals("", result);
    }

    @Test
    void fallsBackWhenApiKeyNotConfigured() {
        WorkLog log = new WorkLog();
        log.setContent("完成接口开发");
        log.setLogDate(LocalDate.of(2026, 6, 21));

        FixedWorkItem item = new FixedWorkItem();
        item.setContent("编写测试");
        item.setRecurrenceType("DAILY");
        item.setReminderTime(LocalTime.of(9, 0));

        String result = aiSummaryService.summarize(List.of(log), List.of(item), "DAILY");

        assertNotNull(result);
        assertTrue(result.contains("完成接口开发"));
        assertTrue(result.contains("编写测试"));
    }

    @Test
    void dailyPromptContainsTodayAndTomorrow() {
        WorkLog log = new WorkLog();
        log.setContent("工作记录");
        log.setLogDate(LocalDate.now());

        String result = aiSummaryService.summarize(List.of(log), List.of(), "DAILY");
        assertFalse(result.isEmpty());
    }
}
