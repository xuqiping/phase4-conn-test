package com.superprogrammer.chat.service.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.llm.LlmGateway;
import com.superprogrammer.llm.dto.LlmResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * MemoryConflictJudge 单测（计划12 · H 收尾瘦身版）。
 * <p>legacy 抽取/路由/三维筛用例已随旧栈删；仅留 E-5 总结时序冲突判定（{@code judgeSummaryConflict}）。
 */
@ExtendWith(MockitoExtension.class)
class MemoryConflictJudgeTest {

    @Mock private LlmGateway llmGateway;

    private MemoryConflictJudge judge;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        judge = new MemoryConflictJudge(llmGateway, objectMapper);
    }

    private com.superprogrammer.chat.entity.MemorySummary summary(Long id, String l1) {
        com.superprogrammer.chat.entity.MemorySummary s = new com.superprogrammer.chat.entity.MemorySummary();
        s.setId(id);
        s.setL1Summary(l1);
        return s;
    }

    @Test
    void judgeSummaryConflict_emptyExisting_noLlmCall() {
        var r = judge.judgeSummaryConflict(List.of(), "新总结", 7L);
        assertFalse(r.conflict(), "无已有总结 → 不冲突");
        verifyNoInteractions(llmGateway);
    }

    @Test
    void judgeSummaryConflict_blankNew_failSafe() {
        var r = judge.judgeSummaryConflict(List.of(summary(1L, "旧")), "  ", 7L);
        assertFalse(r.conflict(), "新总结空 → fail-safe 不冲突");
        verifyNoInteractions(llmGateway);
    }

    @Test
    void judgeSummaryConflict_conflictTrueParsed() {
        when(llmGateway.chat(any(), any())).thenReturn(LlmResponse.builder().content(
                "{\"conflict\":true,\"askText\":\"旧「住北京」与新「住上海」冲突，保留哪条？\"}").build());

        var r = judge.judgeSummaryConflict(List.of(summary(1L, "2024 住北京")), "2026 住上海", 7L);

        assertTrue(r.conflict(), "时序互斥 → 冲突");
        assertNotNull(r.askText());
        assertTrue(r.askText().contains("住北京"));
    }

    @Test
    void judgeSummaryConflict_coexistFalseParsed() {
        when(llmGateway.chat(any(), any())).thenReturn(LlmResponse.builder().content(
                "{\"conflict\":false,\"askText\":\"\"}").build());

        var r = judge.judgeSummaryConflict(List.of(summary(1L, "会 Java")), "也会 Python", 7L);

        assertFalse(r.conflict(), "并存互补 → 不冲突");
        assertNull(r.askText(), "无冲突 askText 规范为 null");
    }

    @Test
    void judgeSummaryConflict_nonJsonFailSafe() {
        when(llmGateway.chat(any(), any())).thenReturn(LlmResponse.builder().content("not a json").build());

        var r = judge.judgeSummaryConflict(List.of(summary(1L, "旧")), "新", 7L);

        assertFalse(r.conflict(), "非 JSON → fail-safe 不冲突");
    }

    @Test
    void judgeSummaryConflict_llmThrowsFailSafe() {
        when(llmGateway.chat(any(), any())).thenThrow(new RuntimeException("LLM 宕机"));

        var r = judge.judgeSummaryConflict(List.of(summary(1L, "旧")), "新", 7L);

        assertFalse(r.conflict(), "LLM 异常 → fail-safe 不冲突");
    }
}
