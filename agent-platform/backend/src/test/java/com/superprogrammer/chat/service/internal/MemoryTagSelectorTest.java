package com.superprogrammer.chat.service.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.chat.dto.RecallTagMeta;
import com.superprogrammer.chat.mapper.MemoryTagMapper;
import com.superprogrammer.knowledge.service.RagConfig;
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
 * 计划12 · D-3 · MemoryTagSelector 单测（Mockito，mock mapper + llmGateway，真实 ObjectMapper 解析）。
 * <p>
 * 覆盖（对齐 §3.3 ③ + 降级链 + 向量 3/12）：
 * <ol>
 *   <li>空标签 → 返空，不调 LLM。</li>
 *   <li>≤30 全灌 LLM 精选（不粗筛，rankByAnchor* 不调）。</li>
 *   <li>LLM 返 {@code []} → 返空（明确无相关）。</li>
 *   <li>LLM 异常 → 降级返 candidates 全集。</li>
 *   <li>&gt;30 → embed + rankByHalfvec + rankByTsv + RRF + LLM 精选。</li>
 *   <li>&gt;30 embed 失败 → 单路 BM25 继续（halfvec 不阻断）。</li>
 *   <li>&gt;30 双路全失败 → usage 前 30 灌 LLM。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class MemoryTagSelectorTest {

    @Mock
    MemoryTagMapper tagMapper;

    @Mock
    LlmGateway llmGateway;

    @Mock
    com.superprogrammer.system.service.SystemSettingService systemSettingService;

    private MemoryTagSelector selector;

    @BeforeEach
    void setUp() {
        lenient().when(systemSettingService.getMemoryJudgeModel()).thenReturn("doubao-seed-2.0-code");
        selector = new MemoryTagSelector(tagMapper, llmGateway, new ObjectMapper(), systemSettingService);
    }

    private static RecallTagMeta meta(long id) {
        RecallTagMeta m = new RecallTagMeta();
        m.setId(id);
        m.setOwnerUserId(1L);
        m.setSubject("我");
        m.setTopic("t" + id);
        m.setLabel("l" + id);
        m.setUsageCount((int) (100 - id));
        return m;
    }

    private static List<RecallTagMeta> metas(int n) {
        List<RecallTagMeta> list = new ArrayList<>();
        for (long i = 1; i <= n; i++) list.add(meta(i));
        return list;
    }

    private void mockChatReturn(String content) {
        LlmResponse resp = mock(LlmResponse.class);
        when(resp.getContent()).thenReturn(content);
        when(llmGateway.chat(any(), eq(1L))).thenReturn(resp);
    }

    // ===== 空标签 =====

    @Test
    void emptyTags_returnsEmpty_noLlm() {
        assertTrue(selector.select("q", List.of(), 1L, null).isEmpty());
        verifyNoInteractions(llmGateway);
    }

    // ===== ≤30 全灌 LLM =====

    @Test
    void under30_fullPassToLlm_noCoarsen() {
        List<RecallTagMeta> tags = metas(5);
        mockChatReturn("[1,3]");
        List<RecallTagMeta> r = selector.select("q", tags, 1L, null);
        assertEquals(List.of(1L, 3L), r.stream().map(RecallTagMeta::getId).toList());
        verify(tagMapper, never()).rankByAnchorHalfvec(anyList(), anyString(), anyInt());
        verify(tagMapper, never()).rankByAnchorTsv(anyList(), anyString(), anyInt());
        verify(llmGateway, never()).embed(anyString(), anyString(), anyLong());
    }

    @Test
    void under30_llmReturnsEmpty_emptyResult() {
        mockChatReturn("[]");
        assertTrue(selector.select("q", metas(5), 1L, null).isEmpty());
    }

    @Test
    void under30_llmThrows_degradeToCandidates() {
        when(llmGateway.chat(any(), eq(1L))).thenThrow(new RuntimeException("LLM down"));
        List<RecallTagMeta> tags = metas(5);
        List<RecallTagMeta> r = selector.select("q", tags, 1L, null);
        // 降级：返 candidates 全集（5 条，未精筛）
        assertEquals(5, r.size());
    }

    @Test
    void under30_llmDirtyJson_retryThenDegrade() {
        // 前两次脏 JSON，第三次也脏 → 重试 3 次全失败 → 降级 candidates
        mockChatReturn("not a json");
        List<RecallTagMeta> r = selector.select("q", metas(3), 1L, null);
        assertEquals(3, r.size());
        verify(llmGateway, times(3)).chat(any(), eq(1L));
    }

    // ===== >30 RRF 粗筛 =====

    @Test
    void over30_rrfCoarsen_thenLlm() {
        List<RecallTagMeta> tags = metas(40);
        when(llmGateway.embed(anyString(), eq(RagConfig.MEMORY_EMBED_MODEL), eq(1L)))
                .thenReturn(new float[]{0.1f, 0.2f});
        // 路 A 返前 30 id（按 id 升序模拟距离序）
        when(tagMapper.rankByAnchorHalfvec(anyList(), anyString(), anyInt()))
                .thenReturn(metas(30).stream().map(RecallTagMeta::getId).toList());
        when(tagMapper.rankByAnchorTsv(anyList(), anyString(), anyInt()))
                .thenReturn(metas(30).stream().map(RecallTagMeta::getId).toList());
        mockChatReturn("[1,2]");

        List<RecallTagMeta> r = selector.select("q", tags, 1L, null);

        verify(llmGateway).embed(anyString(), eq(RagConfig.MEMORY_EMBED_MODEL), eq(1L));
        verify(tagMapper).rankByAnchorHalfvec(anyList(), anyString(), anyInt());
        verify(tagMapper).rankByAnchorTsv(anyList(), anyString(), anyInt());
        assertEquals(List.of(1L, 2L), r.stream().map(RecallTagMeta::getId).toList());
    }

    @Test
    void over30_embedFails_bm25SinglePath() {
        List<RecallTagMeta> tags = metas(40);
        when(llmGateway.embed(anyString(), anyString(), anyLong())).thenThrow(new RuntimeException("embed down"));
        when(tagMapper.rankByAnchorTsv(anyList(), anyString(), anyInt()))
                .thenReturn(metas(30).stream().map(RecallTagMeta::getId).toList());
        mockChatReturn("[5]");

        List<RecallTagMeta> r = selector.select("q", tags, 1L, null);

        // halfvec 失败不阻断，单路 BM25 继续
        verify(tagMapper).rankByAnchorTsv(anyList(), anyString(), anyInt());
        assertEquals(List.of(5L), r.stream().map(RecallTagMeta::getId).toList());
    }

    @Test
    void over30_bothPathsFail_usageTopFeedLlm() {
        List<RecallTagMeta> tags = metas(40);
        when(llmGateway.embed(anyString(), anyString(), anyLong())).thenThrow(new RuntimeException("embed down"));
        // BM25 返空（无 token 命中）
        when(tagMapper.rankByAnchorTsv(anyList(), anyString(), anyInt())).thenReturn(List.of());
        mockChatReturn("[1]");

        List<RecallTagMeta> r = selector.select("q", tags, 1L, null);

        // 双路空 → usage 前 30 灌 LLM（仍精选）
        assertEquals(List.of(1L), r.stream().map(RecallTagMeta::getId).toList());
        verify(llmGateway).chat(any(), eq(1L));
    }
}
