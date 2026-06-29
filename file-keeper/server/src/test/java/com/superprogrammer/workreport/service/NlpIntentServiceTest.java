package com.superprogrammer.workreport.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class NlpIntentServiceTest {

    private final DateParseService dateParseService = new DateParseService();

    private NlpIntentService service() {
        return new NlpIntentService(dateParseService, new NoOpLlmIntentClient());
    }

    @Test
    void shouldParseCompleteFixedWork() {
        NlpIntentService.IntentResult result = service().parse(1L, "完成日报设计");
        assertThat(result.intent()).isEqualTo("complete_fixed_work");
        assertThat(result.confidence()).isGreaterThanOrEqualTo(0.85);
        assertThat(result.entities().get("task_name")).isEqualTo("日报设计");
    }

    @Test
    void shouldParseAddWorkLog() {
        NlpIntentService.IntentResult result = service().parse(1L, "今天做了需求评审");
        assertThat(result.intent()).isEqualTo("add_work_log");
        assertThat(result.entities().get("content")).isEqualTo("需求评审");
    }

    @Test
    void shouldParseAddInspiration() {
        NlpIntentService.IntentResult result = service().parse(1L, "灵感：AI 日报应支持情感分析 #产品/灵感");
        assertThat(result.intent()).isEqualTo("add_inspiration");
        assertThat(result.entities().get("content")).isEqualTo("AI 日报应支持情感分析 #产品/灵感");
        assertThat(result.entities().get("tags")).isEqualTo(List.of("产品/灵感"));
    }

    @Test
    void shouldReturnUnknownForUnrecognized() {
        NlpIntentService.IntentResult result = service().parse(1L, "你好");
        assertThat(result.intent()).isEqualTo("unknown");
    }

    static class NoOpLlmIntentClient extends LlmIntentClient {
        NoOpLlmIntentClient() {
            super(null, null, null);
        }

        @Override
        public Map<String, Object> parseIntent(Long userId, String text) {
            return null;
        }
    }
}
