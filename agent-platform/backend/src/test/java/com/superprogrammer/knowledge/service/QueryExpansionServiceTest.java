package com.superprogrammer.knowledge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.knowledge.config.RagRecallProperties;
import com.superprogrammer.knowledge.service.QueryExpansionService.ExpandedQuery;
import com.superprogrammer.knowledge.util.HalfVecUtil;
import com.superprogrammer.llm.LlmGateway;
import com.superprogrammer.llm.dto.LlmResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * QueryExpansionService 多路扩展：禁用→单规范；失败→降级；正常→规范+释义+HyDE。
 */
@ExtendWith(MockitoExtension.class)
class QueryExpansionServiceTest {

    @Mock private LlmGateway llmGateway;

    private RagRecallProperties props;
    private QueryExpansionService service;

    @BeforeEach
    void setUp() {
        props = new RagRecallProperties();
        service = new QueryExpansionService(llmGateway, new ObjectMapper(), props);
    }

    @Test
    void disabled_returnsCanonicalOnly_noChatCall() {
        props.setEnabled(false);
        when(llmGateway.embed(anyString(), anyString())).thenReturn(new float[HalfVecUtil.DIM]);

        ExpandedQuery eq = service.expand("如何安装部署我的系统", "doubao-embedding-vision");

        assertEquals(1, eq.qHalfs().size());
        verify(llmGateway, never()).chat(any());   // 禁用 → 不做释义 LLM 调用
    }

    @Test
    void expansionDisabled_returnsCanonicalOnly() {
        props.getExpansion().setEnabled(false);
        when(llmGateway.embed(anyString(), anyString())).thenReturn(new float[HalfVecUtil.DIM]);

        ExpandedQuery eq = service.expand("q", "m");

        assertEquals(1, eq.qHalfs().size());
        verify(llmGateway, never()).chat(any());
    }

    @Test
    void happyPath_canonicalPlusParaphrasesPlusHyde() {
        // 2 释义 + 1 HyDE；规范 query 第一个
        when(llmGateway.chat(any())).thenReturn(LlmResponse.builder().content(
                "{\"paraphrases\":[\"系统安装部署步骤\",\"怎么部署系统\"],\"hyde\":\"本系统安装部署文档...\"}").build());
        // 每次 embed 返回不同向量（否则 halfvec 相同被去重成 1 条）
        final int[] counter = {0};
        when(llmGateway.embed(anyString(), anyString())).thenAnswer(inv -> {
            float[] v = new float[HalfVecUtil.DIM];
            v[0] = ++counter[0];
            return v;
        });

        ExpandedQuery eq = service.expand("如何安装部署我的系统", "m");

        // 规范(1) + 2 释义 + 1 HyDE = 4（向量各不同）
        assertEquals(4, eq.qHalfs().size());
        verify(llmGateway, times(1)).chat(any());
        verify(llmGateway, times(4)).embed(anyString(), anyString());
    }

    @Test
    void chatThrows_fallsBackToCanonicalOnly() {
        when(llmGateway.embed(anyString(), anyString())).thenReturn(new float[HalfVecUtil.DIM]);
        when(llmGateway.chat(any())).thenThrow(new RuntimeException("LLM 宕机"));

        ExpandedQuery eq = service.expand("q", "m");

        // 释义失败不致命 → 仅规范 query
        assertEquals(1, eq.qHalfs().size());
    }

    @Test
    void embedFails_returnsEmpty() {
        // 规范 embed 都失败 → 空 qHalfs，上层兜底
        when(llmGateway.embed(anyString(), anyString())).thenThrow(new RuntimeException("embed 挂"));

        ExpandedQuery eq = service.expand("q", "m");

        assertTrue(eq.qHalfs().isEmpty());
    }
}
