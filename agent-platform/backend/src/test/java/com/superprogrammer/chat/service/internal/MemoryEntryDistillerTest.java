package com.superprogrammer.chat.service.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.chat.entity.MemoryProjectRule;
import com.superprogrammer.llm.LlmGateway;
import com.superprogrammer.llm.dto.LlmResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 记忆二期 P1 · 路由精判蒸馏器单测（FR-003）。
 * 核心：schema 校验、脏 JSON 清洗重试、幻觉 project_id 丢弃、LLM 全失败降级空集。
 */
@ExtendWith(MockitoExtension.class)
class MemoryEntryDistillerTest {

    @Mock
    private LlmGateway llmGateway;

    private MemoryEntryDistiller distiller;

    @BeforeEach
    void setUp() {
        distiller = new MemoryEntryDistiller(llmGateway, new ObjectMapper());
    }

    private MemoryProjectRule rule(long projectId) {
        MemoryProjectRule r = new MemoryProjectRule();
        r.setId(projectId * 10);
        r.setProjectId(projectId);
        r.setRuleText("涉及 SeedDance 的讨论");
        r.setPositiveExamples(List.of("cfg 参数怎么调"));
        r.setNegativeExamples(List.of());
        return r;
    }

    private LlmResponse resp(String content) {
        return LlmResponse.builder().content(content).build();
    }

    // AC-FR-003：规范 JSON 一次过；hit/miss 混合
    @Test
    void judge_validJson_parses() {
        when(llmGateway.chat(any(), anyLong())).thenReturn(resp(
                "{\"results\":[{\"project_id\":1,\"hit\":true,\"confidence\":0.9,"
                        + "\"distilled_l1\":\"问了 cfg 参数\",\"distilled_l2\":\"细节\"},"
                        + "{\"project_id\":2,\"hit\":false,\"confidence\":0.1,\"distilled_l1\":\"\",\"distilled_l2\":\"\"}]}"));

        List<MemoryEntryDistiller.Judgment> out = distiller.judge(100L, List.of(rule(1L), rule(2L)), "聊了 cfg", null, "doubao-seed-2.0-code");

        assertEquals(2, out.size());
        assertTrue(out.get(0).hit());
        assertEquals(0.9, out.get(0).confidence());
        assertEquals("问了 cfg 参数", out.get(0).distilledL1());
        assertTrue(!out.get(1).hit());
    }

    // AC-FR-003：脏 JSON（```fence + 前后解释文字）→ 清洗后解析成功
    @Test
    void judge_dirtyJson_cleaned() {
        when(llmGateway.chat(any(), anyLong())).thenReturn(resp(
                "好的，判定如下：\n```json\n{\"results\":[{\"project_id\":1,\"hit\":true,\"confidence\":0.85,"
                        + "\"distilled_l1\":\"蒸馏\",\"distilled_l2\":\"\"}]}\n```\n以上。"));

        List<MemoryEntryDistiller.Judgment> out = distiller.judge(100L, List.of(rule(1L)), "聊了 cfg", null, "doubao-seed-2.0-code");

        assertEquals(1, out.size());
        assertEquals("蒸馏", out.get(0).distilledL1());
    }

    // schema 不合规（hit=true 但无 distilled_l1）→ 重试 1 次，仍败 → 空集（不收录）
    @Test
    void judge_schemaViolation_retriesThenEmpty() {
        when(llmGateway.chat(any(), anyLong())).thenReturn(resp(
                "{\"results\":[{\"project_id\":1,\"hit\":true,\"confidence\":0.9,\"distilled_l1\":\"\",\"distilled_l2\":\"\"}]}"));

        List<MemoryEntryDistiller.Judgment> out = distiller.judge(100L, List.of(rule(1L)), "聊了 cfg", null, "doubao-seed-2.0-code");

        assertTrue(out.isEmpty());
        verify(llmGateway, times(2)).chat(any(), anyLong());   // 1 次重试
    }

    // 幻觉 project_id（不在候选集）→ 丢该条不连累整批
    @Test
    void judge_hallucinatedProjectId_dropped() {
        when(llmGateway.chat(any(), anyLong())).thenReturn(resp(
                "{\"results\":[{\"project_id\":999,\"hit\":true,\"confidence\":0.9,"
                        + "\"distilled_l1\":\"幻觉\",\"distilled_l2\":\"\"}]}"));

        List<MemoryEntryDistiller.Judgment> out = distiller.judge(100L, List.of(rule(1L)), "聊了 cfg", null, "doubao-seed-2.0-code");

        assertTrue(out.isEmpty());
        verify(llmGateway, times(1)).chat(any(), anyLong());   // 非 schema 错误不重试
    }

    // LLM 异常 → 重试后空集降级
    @Test
    void judge_llmThrows_emptyFallback() {
        when(llmGateway.chat(any(), anyLong())).thenThrow(new RuntimeException("provider down"));

        List<MemoryEntryDistiller.Judgment> out = distiller.judge(100L, List.of(rule(1L)), "聊了 cfg", null, "doubao-seed-2.0-code");

        assertTrue(out.isEmpty());
        verify(llmGateway, times(2)).chat(any(), anyLong());
    }

    // 空候选 → 不调 LLM
    @Test
    void judge_emptyCandidates_noLlm() {
        assertTrue(distiller.judge(100L, List.of(), "x", null, "doubao-seed-2.0-code").isEmpty());
        verify(llmGateway, times(0)).chat(any(), anyLong());
    }
}
