package com.superprogrammer.knowledge.service;

import com.superprogrammer.knowledge.config.RagContextualProperties;
import com.superprogrammer.knowledge.entity.KnowledgeDocument;
import com.superprogrammer.llm.LlmGateway;
import com.superprogrammer.llm.dto.LlmResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * LLM 定位表（WP3 Step1）：正常生成+护栏 / JSON 烂尾部分降级 / 治理词过滤 / 开关关零调用。
 */
@ExtendWith(MockitoExtension.class)
class LlmContextualizerTest {

    @Mock private LlmGateway llmGateway;
    private RagContextualProperties props;
    private LlmContextualizer contextualizer;

    private final List<LlmContextualizer.ChunkBrief> chunks = List.of(
            new LlmContextualizer.ChunkBrief("/L0-0/L2-0", "报销标准", "V2.1 版差旅报销金额表"),
            new LlmContextualizer.ChunkBrief("/L0-0/L2-1", "报销流程", "提交后三个工作日审核"));

    private KnowledgeDocument doc() {
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setId(9L);
        doc.setTitle("差旅制度手册");
        return doc;
    }

    @BeforeEach
    void setUp() {
        props = new RagContextualProperties();
        contextualizer = new LlmContextualizer(llmGateway, props);
    }

    @Test
    void normalGeneration_fencedJson_parsedWithGuards() {
        when(llmGateway.chat(any(), eq(7L))).thenReturn(LlmResponse.builder().content("""
                ```json
                [{"path":"/L0-0/L2-0","locator":"第1章 报销标准下的 V2.1 版金额表定义段落"},
                 {"path":"/L0-0/L2-1","locator":"本条定位语长度为五十五个字符用于验证超长截断逻辑会把超出五十个字符的部分全部裁掉并且只保留前五十个字符"},{"path":"/L0-0/L2-9","locator":"幻觉路径应被丢弃"}]
                ```
                """).build());

        Map<String, String> out = contextualizer.generateLocators(doc(), "差旅制度全文摘要", chunks, 7L);

        assertEquals(2, out.size());
        assertEquals("第1章 报销标准下的 V2.1 版金额表定义段落", out.get("/L0-0/L2-0"));
        assertEquals(50, out.get("/L0-0/L2-1").length());   // 超长截断
        assertFalse(out.containsKey("/L0-0/L2-9"));          // 幻觉 path 丢弃
        // 计费归户 docOwner + maxTokens 预算给足（坑点预判 2000）
        ArgumentCaptor<com.superprogrammer.llm.dto.LlmRequest> cap =
                ArgumentCaptor.forClass(com.superprogrammer.llm.dto.LlmRequest.class);
        verify(llmGateway).chat(cap.capture(), eq(7L));
        assertEquals(2000, cap.getValue().getMaxTokens());
        assertTrue(cap.getValue().getMessages().get(1).getContent().contains("/L0-0/L2-0"));
    }

    @Test
    void truncatedJson_partialChunksDegrade_perChunkIndependent() {
        when(llmGateway.chat(any(), any())).thenReturn(LlmResponse.builder().content(
                """
                [{"path":"/L0-0/L2-0","locator":"第1章 报销标准金额表"},{"path":"/L0-0/L2-1","loc""").build());

        Map<String, String> out = contextualizer.generateLocators(doc(), null, chunks, 7L);

        // 烂尾：能解析出的前项保留，缺失项=该 chunk 降级纯规则（非整文档失败）
        assertEquals(Map.of("/L0-0/L2-0", "第1章 报销标准金额表"), out);
    }

    @Test
    void governanceWordLocator_dropped() {
        when(llmGateway.chat(any(), any())).thenReturn(LlmResponse.builder().content(
                """
                [{"path":"/L0-0/L2-0","locator":"仅授权所有者可见的报销标准"},{"path":"/L0-0/L2-1","locator":"第1章 报销流程说明"}]""").build());

        Map<String, String> out = contextualizer.generateLocators(doc(), null, chunks, 7L);

        // 强治理词（所有者/可见性等）→ 整条丢弃降级，词级替换有残留风险不采用
        assertEquals(Map.of("/L0-0/L2-1", "第1章 报销流程说明"), out);
    }

    @Test
    void subjectWordLocator_kept() {
        when(llmGateway.chat(any(), any())).thenReturn(LlmResponse.builder().content(
                """
                [{"path":"/L0-0/L2-0","locator":"第2章 核心条款 第十条 跨库导出双审批权限条款"},{"path":"/L0-0/L2-1","locator":"权限矩阵表：各角色审批权限速查"}]""").build());

        Map<String, String> out = contextualizer.generateLocators(doc(), null, chunks, 7L);

        // Bug #9 回归：「权限/授权」是权限矩阵类文档的内容主题词而非访问控制元数据——
        // 保留（否则主题涉权限的文档系统性拿不到定位语，KB15 perm_matrix 实测 contextual_text 恒 NULL）
        assertEquals(2, out.size());
        assertTrue(out.get("/L0-0/L2-0").contains("第十条"));
        assertTrue(out.get("/L0-0/L2-1").contains("权限矩阵"));
    }

    @Test
    void disabled_zeroLlmCalls_emptyMap() {
        props.getLlm().setEnabled(false);

        Map<String, String> out = contextualizer.generateLocators(doc(), null, chunks, 7L);

        assertTrue(out.isEmpty());
        verifyNoInteractions(llmGateway);
    }

    @Test
    void chunkCap_excessDegrades_llmFailureWholeDocDegrades() {
        props.getLlm().setMaxChunks(1);
        when(llmGateway.chat(any(), any())).thenReturn(LlmResponse.builder().content("烂 JSON").build());

        // 清单截断到 1：超限 chunk 不进 prompt（降级）；坏输出整体降级=空表不抛
        Map<String, String> out = contextualizer.generateLocators(doc(), null, chunks, 7L);

        assertTrue(out.isEmpty());
        ArgumentCaptor<com.superprogrammer.llm.dto.LlmRequest> cap =
                ArgumentCaptor.forClass(com.superprogrammer.llm.dto.LlmRequest.class);
        verify(llmGateway).chat(cap.capture(), any());
        assertTrue(cap.getValue().getMessages().get(1).getContent().contains("/L0-0/L2-0"));
        assertFalse(cap.getValue().getMessages().get(1).getContent().contains("/L0-0/L2-1"));
    }
}
