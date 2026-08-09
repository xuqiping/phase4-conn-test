package com.superprogrammer.chat.service.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.chat.dto.RecalledSummary;
import com.superprogrammer.chat.entity.MemorySummary;
import com.superprogrammer.chat.mapper.MemorySummaryMapper;
import com.superprogrammer.llm.LlmGateway;
import com.superprogrammer.llm.dto.LlmResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 计划12 · D-4 · MemorySummaryReader 单测（Mockito，mock mapper + llmGateway）。
 * <p>
 * 覆盖（对齐 §3.3 ④⑤ + 降级 + 向量 14）：
 * <ol>
 *   <li>tagIds 空 → 返空。</li>
 *   <li>mapper 返空 → 返空（走 turns）。</li>
 *   <li>≤5 条 → 全 includeL2=true，不调 LLM。</li>
 *   <li>>5 条 reflect 返选中 → 命中 true / 未命中 false。</li>
 *   <li>>5 条 reflect 失败 → 全 false（只读 L1 降级）。</li>
 *   <li>mapper 入参透传（tagIds/scope/timeWindow）。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class MemorySummaryReaderTest {

    @Mock
    MemorySummaryMapper summaryMapper;

    @Mock
    LlmGateway llmGateway;

    @Mock
    com.superprogrammer.system.service.SystemSettingService systemSettingService;

    private MemorySummaryReader reader;

    @BeforeEach
    void setUp() {
        lenient().when(systemSettingService.getMemoryJudgeModel()).thenReturn("doubao-seed-2.0-code");
        reader = new MemorySummaryReader(summaryMapper, llmGateway, new ObjectMapper(), systemSettingService);
    }

    private static MemorySummary summary(long id) {
        MemorySummary s = new MemorySummary();
        s.setId(id);
        s.setUserId(1L);
        s.setTagId(10L);
        s.setL1Summary("概要" + id);
        s.setL2Detail("详述" + id);
        s.setStatus("CLEAN");
        return s;
    }

    private static List<MemorySummary> summaries(int n) {
        List<MemorySummary> list = new ArrayList<>();
        for (long i = 1; i <= n; i++) list.add(summary(i));
        return list;
    }

    private static RecallScope personalScope() {
        return RecallScope.defaultPersonalOnly();
    }

    private void mockChatReturn(String content) {
        LlmResponse resp = mock(LlmResponse.class);
        when(resp.getContent()).thenReturn(content);
        when(llmGateway.chat(any(), eq(1L))).thenReturn(resp);
    }

    // ===== 空路径 =====

    @Test
    void emptyTagIds_returnsEmpty() {
        assertTrue(reader.read("q", List.of(), personalScope(), 1L, null).isEmpty());
        verifyNoInteractions(summaryMapper);
    }

    @Test
    void mapperReturnsEmpty_returnsEmpty() {
        when(summaryMapper.findSummariesForRecall(anyLong(), anyList(), any(), anyBoolean(), any(), any(), any()))
                .thenReturn(List.of());
        assertTrue(reader.read("q", List.of(10L), personalScope(), 1L, null).isEmpty());
        verifyNoInteractions(llmGateway);
    }

    // ===== ≤5 全 L2 =====

    @Test
    void underThreshold_allIncludeL2_noLlm() {
        when(summaryMapper.findSummariesForRecall(anyLong(), anyList(), any(), anyBoolean(), any(), any(), any()))
                .thenReturn(summaries(5));
        List<RecalledSummary> r = reader.read("q", List.of(10L), personalScope(), 1L, null);
        assertEquals(5, r.size());
        assertTrue(r.stream().allMatch(RecalledSummary::includeL2));
        verifyNoInteractions(llmGateway);
    }

    // ===== >5 reflect =====

    @Test
    void overThreshold_reflectSelectsDeepRead() {
        when(summaryMapper.findSummariesForRecall(anyLong(), anyList(), any(), anyBoolean(), any(), any(), any()))
                .thenReturn(summaries(6));
        mockChatReturn("[1,3]");

        List<RecalledSummary> r = reader.read("q", List.of(10L), personalScope(), 1L, null);

        assertEquals(6, r.size());
        // id=1/3 深读 L2，其余只读 L1
        assertTrue(byId(r, 1).includeL2());
        assertTrue(byId(r, 3).includeL2());
        assertFalse(byId(r, 2).includeL2());
        assertFalse(byId(r, 4).includeL2());
    }

    @Test
    void overThreshold_reflectReturnsEmpty_allL1Only() {
        when(summaryMapper.findSummariesForRecall(anyLong(), anyList(), any(), anyBoolean(), any(), any(), any()))
                .thenReturn(summaries(6));
        mockChatReturn("[]");

        List<RecalledSummary> r = reader.read("q", List.of(10L), personalScope(), 1L, null);

        // LLM 明确判无深读 → 全只读 L1
        assertTrue(r.stream().noneMatch(RecalledSummary::includeL2));
    }

    @Test
    void overThreshold_reflectFails_allL1Only() {
        when(summaryMapper.findSummariesForRecall(anyLong(), anyList(), any(), anyBoolean(), any(), any(), any()))
                .thenReturn(summaries(6));
        when(llmGateway.chat(any(), eq(1L))).thenThrow(new RuntimeException("LLM down"));

        List<RecalledSummary> r = reader.read("q", List.of(10L), personalScope(), 1L, null);

        // 降级：全只读 L1（设计「reflect 失败→只读 L1」）
        assertTrue(r.stream().noneMatch(RecalledSummary::includeL2));
    }

    // ===== 参数透传 =====

    @Test
    void mapperArgs_passedThrough() {
        when(summaryMapper.findSummariesForRecall(anyLong(), anyList(), any(), anyBoolean(), any(), any(), any()))
                .thenReturn(List.of());
        RecallTimeWindow tw = new RecallTimeWindow(7, null, null);
        RecallScope scope = new RecallScope(true, List.of(), RecallDirection.INPUT, tw, true);
        reader.read("q", List.of(10L, 20L), scope, 1L, null);
        verify(summaryMapper).findSummariesForRecall(eq(1L), eq(List.of(10L, 20L)), eq(List.of()),
                eq(true), isNull(), isNull(), eq(7));
    }

    private static RecalledSummary byId(List<RecalledSummary> r, long id) {
        return r.stream().filter(x -> x.summary().getId() == id).findFirst().orElseThrow();
    }
}
