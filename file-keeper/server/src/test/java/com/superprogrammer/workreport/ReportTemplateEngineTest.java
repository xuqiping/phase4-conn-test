package com.superprogrammer.workreport;

import com.superprogrammer.workreport.entity.FixedWorkItem;
import com.superprogrammer.workreport.entity.WorkLog;
import com.superprogrammer.workreport.service.ReportTemplateEngine;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ReportTemplateEngineTest {

    private final ReportTemplateEngine engine = new ReportTemplateEngine();

    @Test
    void renderReplacesKnownPlaceholders() {
        String template = "{{ai_summary}}\n{{logs}}\n{{fixed_work}}";
        WorkLog log = new WorkLog();
        log.setContent("完成接口");
        log.setLogDate(LocalDate.of(2026, 6, 21));

        FixedWorkItem item = new FixedWorkItem();
        item.setContent("写文档");
        item.setRecurrenceType("DAILY");
        item.setReminderTime(LocalTime.of(9, 0));

        Map<String, Object> context = engine.buildContext("AI总结", List.of(log), List.of(item), "DAILY");
        String result = engine.render(template, context);

        assertTrue(result.contains("AI总结"));
        assertTrue(result.contains("- 完成接口"));
        assertTrue(result.contains("- 写文档"));
    }

    @Test
    void plansPlaceholderIsBackwardCompatible() {
        String template = "{{plans}}";
        FixedWorkItem item = new FixedWorkItem();
        item.setContent("例行工作");
        item.setRecurrenceType("DAILY");
        item.setReminderTime(LocalTime.of(9, 0));

        Map<String, Object> context = engine.buildContext("", List.of(), List.of(item), "DAILY");
        String result = engine.render(template, context);

        assertTrue(result.contains("- 例行工作"));
    }

    @Test
    void unmatchedPlaceholdersBecomeEmpty() {
        String template = "{{logs}}\n{{issues}}";
        String result = engine.render(template, Map.of("logs", "- 记录"));

        assertEquals("- 记录\n", result);
    }

    @Test
    void nullValueBecomesEmpty() {
        String template = "{{key}}";
        Map<String, Object> context = new HashMap<>();
        context.put("key", null);
        String result = engine.render(template, context);

        assertEquals("", result);
    }
}
