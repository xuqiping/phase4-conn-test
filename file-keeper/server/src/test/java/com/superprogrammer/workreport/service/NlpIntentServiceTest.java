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
        assertComplete("完成日报设计", "日报设计");
        assertComplete("做完了日报设计", "日报设计");
        assertComplete("搞定日报设计", "日报设计");
        assertComplete("标记完成日报设计", "日报设计");
        assertComplete("标记日报设计为完成", "日报设计");
        assertComplete("把日报设计标记为完成", "日报设计");
        assertComplete("将日报设计标记完成", "日报设计");
        assertComplete("日报设计标记为完成", "日报设计");
        assertComplete("日报设计完成了", "日报设计");
        assertComplete("done with daily report design", "daily report design");
        assertComplete("finish daily report design", "daily report design");
        assertComplete("finished daily report design", "daily report design");
        assertComplete("completed daily report design", "daily report design");
    }

    private void assertComplete(String text, String expectedTask) {
        NlpIntentService.IntentResult result = service().parse(1L, text);
        assertThat(result.intent()).as("intent for: " + text).isEqualTo("complete_fixed_work");
        assertThat(result.confidence()).as("confidence for: " + text).isGreaterThanOrEqualTo(0.85);
        assertThat(result.entities().get("task_name")).as("task_name for: " + text).isEqualTo(expectedTask);
    }

    @Test
    void shouldReturnUnknownWhenOnlySayDoneWithoutTaskName() {
        assertThat(service().parse(1L, "我完成了").intent()).isEqualTo("unknown");
        assertThat(service().parse(1L, "我搞定了").intent()).isEqualTo("unknown");
        assertThat(service().parse(1L, "我标记为完成").intent()).isEqualTo("unknown");
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

    @Test
    void shouldParseHelpCommand() {
        assertThat(service().parse(1L, "/help").intent()).isEqualTo("help");
        assertThat(service().parse(1L, "help").intent()).isEqualTo("help");
        assertThat(service().parse(1L, "帮助").intent()).isEqualTo("help");
        assertThat(service().parse(1L, "怎么用").intent()).isEqualTo("help");
        assertThat(service().parse(1L, "?").intent()).isEqualTo("help");
    }

    @Test
    void helpIntentShouldHaveHighConfidence() {
        NlpIntentService.IntentResult result = service().parse(1L, "/help");
        assertThat(result.confidence()).isGreaterThanOrEqualTo(0.85);
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
