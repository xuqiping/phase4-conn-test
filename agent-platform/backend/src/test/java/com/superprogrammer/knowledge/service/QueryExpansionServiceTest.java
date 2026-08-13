package com.superprogrammer.knowledge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.knowledge.config.RagRecallProperties;
import com.superprogrammer.knowledge.service.QueryExpansionService.ExpandedQuery;
import com.superprogrammer.knowledge.trace.RagTraceContext;
import com.superprogrammer.knowledge.util.HalfVecUtil;
import com.superprogrammer.llm.LlmGateway;
import com.superprogrammer.llm.dto.LlmResponse;
import com.superprogrammer.system.service.SystemSettingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * QueryExpansionService 多路扩展：
 * 开关关→单规范；短 query→改写+HyDE；长 query(>阈值)→切块多路不调改写 LLM；失败→降级。
 * 开关源 = SystemSettingService（运行时全局，4 路同读）。
 */
@ExtendWith(MockitoExtension.class)
class QueryExpansionServiceTest {

    @Mock private LlmGateway llmGateway;
    @Mock private SystemSettingService systemSettingService;

    private RagRecallProperties props;
    private QueryExpansionService service;

    @BeforeEach
    void setUp() {
        props = new RagRecallProperties();
        service = new QueryExpansionService(llmGateway, new ObjectMapper(), props, systemSettingService);
    }

    @Test
    void disabled_returnsCanonicalOnly_noChatCall() {
        when(systemSettingService.getRagRecallExpansionEnabled()).thenReturn(false);
        when(llmGateway.embed(anyString(), anyString(), any())).thenReturn(new float[HalfVecUtil.DIM]);

        ExpandedQuery eq = service.expand("如何安装部署我的系统", "doubao-embedding-vision", 7L);

        assertEquals(1, eq.qHalfs().size());
        verify(llmGateway, never()).chat(any(), any());   // 禁用 → 不做释义 LLM 调用
    }

    @Test
    void shortQuery_canonicalPlusParaphrasesPlusHyde() {
        when(systemSettingService.getRagRecallExpansionEnabled()).thenReturn(true);
        when(systemSettingService.getRagRecallExpansionThreshold()).thenReturn(200);
        // 2 释义 + 1 HyDE；规范 query 第一个
        when(llmGateway.chat(any(), any())).thenReturn(LlmResponse.builder().content(
                "{\"paraphrases\":[\"系统安装部署步骤\",\"怎么部署系统\"],\"hyde\":\"本系统安装部署文档...\"}").build());
        // 每次 embed 返回不同向量（否则 halfvec 相同被去重成 1 条）
        final int[] counter = {0};
        when(llmGateway.embed(anyString(), anyString(), any())).thenAnswer(inv -> {
            float[] v = new float[HalfVecUtil.DIM];
            v[0] = ++counter[0];
            return v;
        });

        ExpandedQuery eq = service.expand("如何安装部署我的系统", "m", 7L);

        // 规范(1) + 2 释义 + 1 HyDE = 4（向量各不同）
        assertEquals(4, eq.qHalfs().size());
        verify(llmGateway, times(1)).chat(any(), any());
        verify(llmGateway, times(4)).embed(anyString(), anyString(), any());
    }

    @Test
    void longInput_chunksMultiEmbed_noChatCall() {
        when(systemSettingService.getRagRecallExpansionEnabled()).thenReturn(true);
        when(systemSettingService.getRagRecallExpansionThreshold()).thenReturn(20);   // 小阈值触发切块
        final int[] counter = {0};
        when(llmGateway.embed(anyString(), anyString(), any())).thenAnswer(inv -> {
            float[] v = new float[HalfVecUtil.DIM];
            v[0] = ++counter[0];
            return v;
        });
        // >20 字、多句多主题的长 query
        String longQuery = "第一段主题A内容描述。第二句继续A。第二段主题B完全不同。第三段主题C另一回事。";

        ExpandedQuery eq = service.expand(longQuery, "m", 7L);

        // 规范(1) + 多块（>1），不调改写 LLM（切块省 chat 调用）
        assertTrue(eq.qHalfs().size() > 1, "切块应产生多个 qHalf，实际=" + eq.qHalfs().size());
        verify(llmGateway, never()).chat(any(), any());
    }

    @Test
    void chatThrows_fallsBackToCanonicalOnly() {
        when(systemSettingService.getRagRecallExpansionEnabled()).thenReturn(true);
        when(systemSettingService.getRagRecallExpansionThreshold()).thenReturn(200);
        when(llmGateway.embed(anyString(), anyString(), any())).thenReturn(new float[HalfVecUtil.DIM]);
        when(llmGateway.chat(any(), any())).thenThrow(new RuntimeException("LLM 宕机"));

        ExpandedQuery eq = service.expand("q", "m", 7L);

        // 释义失败不致命 → 仅规范 query
        assertEquals(1, eq.qHalfs().size());
    }

    @Test
    void embedFails_returnsEmpty() {
        // 规范 embed 都失败 → 空 qHalfs，上层兜底（不读 enabled）
        when(llmGateway.embed(anyString(), anyString(), any())).thenThrow(new RuntimeException("embed 挂"));

        ExpandedQuery eq = service.expand("q", "m", 7L);

        assertTrue(eq.qHalfs().isEmpty());
    }

    @Test
    void rewriteAndHyde_useDedicatedModelCallPurpose() {
        when(systemSettingService.getRagRecallExpansionEnabled()).thenReturn(true);
        when(systemSettingService.getRagRecallExpansionThreshold()).thenReturn(200);
        when(llmGateway.embed(anyString(), anyString(), any())).thenReturn(new float[HalfVecUtil.DIM]);
        final String[] observedPurpose = {null};
        when(llmGateway.chat(any(), any())).thenAnswer(inv -> {
            observedPurpose[0] = RagTraceContext.current().callPurpose();
            return LlmResponse.builder().content("{\"paraphrases\":[],\"hyde\":\"\"}").build();
        });

        try (var ignored = RagTraceContext.open(new RagTraceContext.State(
                "trace-1", "retrieval-1", null, null, null, 7L, "[1]"))) {
            service.expand("如何安装", "m", 7L);
        }

        assertEquals("QUERY_REWRITE_AND_HYDE", observedPurpose[0]);
        assertNull(RagTraceContext.current());
    }
}
