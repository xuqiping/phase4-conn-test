package com.superprogrammer.knowledge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.knowledge.config.AnswerCacheProperties;
import com.superprogrammer.knowledge.dto.RagQueryRow;
import com.superprogrammer.knowledge.dto.RagRetrieveRequest;
import com.superprogrammer.knowledge.dto.RagRetrieveVO;
import com.superprogrammer.knowledge.entity.KnowledgeBase;
import com.superprogrammer.knowledge.mapper.RagRetrievalLogMapper;
import com.superprogrammer.knowledge.mapper.RagRetrievalQueryMapper;
import com.superprogrammer.knowledge.service.internal.AnswerCacheService;
import com.superprogrammer.knowledge.service.internal.CitationChecker;
import com.superprogrammer.knowledge.service.internal.VisibleDocSet;
import com.superprogrammer.knowledge.util.HalfVecUtil;
import com.superprogrammer.llm.LlmGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * RagRetrievalService 8 步线性检索的关键 gate 测（mock mapper/LLM/可见集/缓存）。
 * 聚焦：权限 gate / NO_VISIBLE_DOCS / NO_DENSE_HITS / B4 单 embed / A2 LOW_CONFIDENCE abstain。
 * A1 重生成/B3 越界/缓存命中更深的路径由 CitationChecker/AnswerCacheService/RagConfig 单测 + Phase D IT 覆盖。
 */
@ExtendWith(MockitoExtension.class)
class RagRetrievalServiceTest {

    @Mock private RagRetrievalQueryMapper queryMapper;
    @Mock private RagRetrievalLogMapper logMapper;
    @Mock private KnowledgeBaseService knowledgeBaseService;
    @Mock private LlmGateway llmGateway;
    @Mock private VisibilitySetService visibilitySetService;
    @Mock private AnswerCacheService answerCacheService;

    private final RagConfig ragConfig = new RagConfig();
    private final CitationChecker citationChecker = new CitationChecker();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AnswerCacheProperties answerCacheProps = new AnswerCacheProperties();  // 默认 enabled=false

    private RagRetrievalService service;

    @BeforeEach
    void setUp() {
        service = new RagRetrievalService(queryMapper, logMapper, knowledgeBaseService, llmGateway,
                ragConfig, citationChecker, objectMapper, visibilitySetService,
                answerCacheService, answerCacheProps);
    }

    @Test
    void forbidden_whenCannotRead() {
        KnowledgeBase kb = kb(1L);
        when(knowledgeBaseService.ensure(1L)).thenReturn(kb);
        when(knowledgeBaseService.canRead(eq(kb), eq(7L), anyBoolean())).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.retrieve(req(1L, "q"), 7L));
        assertTrue(ex.getMessage().contains("无权访问"));
    }

    @Test
    void noVisibleDocs_abstains() {
        KnowledgeBase kb = kb(1L);
        when(knowledgeBaseService.ensure(1L)).thenReturn(kb);
        when(knowledgeBaseService.canRead(eq(kb), eq(7L), anyBoolean())).thenReturn(true);
        when(knowledgeBaseService.canManage(eq(kb), eq(7L), anyBoolean())).thenReturn(false);
        when(visibilitySetService.getVisibleDocs(eq(1L), eq(7L), eq(false)))
                .thenReturn(VisibleDocSet.of(java.util.Collections.emptySet()));  // all=false, 空

        RagRetrieveVO vo = service.retrieve(req(1L, "q"), 7L);

        assertTrue(vo.isAbstained());
        assertEquals("NO_VISIBLE_DOCS", vo.getAbstainReason());
        verify(llmGateway, never()).embed(anyString(), anyString());  // 早 abstain，未 embed
    }

    @Test
    void noDenseHits_abstains() {
        KnowledgeBase kb = kb(1L);
        stubReadableAll(kb);  // 可见集 all=true → step1/3 放行
        when(llmGateway.embed(anyString(), anyString())).thenReturn(new float[HalfVecUtil.DIM]);
        when(queryMapper.denseRecallL0(anyLong(), anyString(), anyBoolean(), anyList(), any(), anyInt()))
                .thenReturn(List.of());  // step5 空召回

        RagRetrieveVO vo = service.retrieve(req(1L, "如何安装"), 7L);

        assertTrue(vo.isAbstained());
        assertEquals("NO_DENSE_HITS", vo.getAbstainReason());
    }

    @Test
    void b4_queryEmbeddedExactlyOnce() {
        KnowledgeBase kb = kb(1L);
        stubReadableAll(kb);
        when(llmGateway.embed(anyString(), anyString())).thenReturn(new float[HalfVecUtil.DIM]);
        when(queryMapper.denseRecallL0(anyLong(), anyString(), anyBoolean(), anyList(), any(), anyInt()))
                .thenReturn(List.of());

        service.retrieve(req(1L, "如何安装"), 7L);

        verify(llmGateway, times(1)).embed(anyString(), anyString());  // B4：query embed 仅一次
    }

    @Test
    void a2_lowConfidenceAbstains_onLowParentSim() {
        KnowledgeBase kb = kb(1L);
        stubReadableAll(kb);
        when(llmGateway.embed(anyString(), anyString())).thenReturn(new float[HalfVecUtil.DIM]);
        // dense 命中 1 个 L0，距离 0.8 → sim 0.2（< abstainThreshold 0.5）
        RagQueryRow.DenseRecallRow dense = new RagQueryRow.DenseRecallRow();
        dense.setNodeId(10L);
        dense.setDocumentId(99L);
        dense.setTitle("安装步骤");
        dense.setCosineDistance(0.8);
        when(queryMapper.denseRecallL0(anyLong(), anyString(), anyBoolean(), anyList(), any(), anyInt()))
                .thenReturn(List.of(dense));
        // step6 L2 子节点：parentL0Sim 继承父 0.2
        RagQueryRow.L2Row l2 = new RagQueryRow.L2Row();
        l2.setNodeId(11L);
        l2.setDocumentId(99L);
        l2.setParentId(10L);
        l2.setTitle("安装步骤");
        l2.setContent("PostgreSQL16/pgvector");
        l2.setContentHash("hash11");
        when(queryMapper.fetchL2Children(anyLong(), anyList(), anyList())).thenReturn(List.of(l2));
        when(queryMapper.bm25Hits(anyLong(), anyString(), anyList())).thenReturn(List.of());

        RagRetrieveVO vo = service.retrieve(req(1L, "如何安装"), 7L);

        assertTrue(vo.isAbstained());
        assertEquals("LOW_CONFIDENCE", vo.getAbstainReason());
        verify(llmGateway, never()).chat(any());  // abstain 不生成
    }

    // ============================ helpers ============================

    /** 非管理员但可见集 all=true（覆盖 step1/3 放行到 step5）。 */
    private void stubReadableAll(KnowledgeBase kb) {
        when(knowledgeBaseService.ensure(kb.getId())).thenReturn(kb);
        when(knowledgeBaseService.canRead(eq(kb), eq(7L), anyBoolean())).thenReturn(true);
        when(knowledgeBaseService.canManage(eq(kb), eq(7L), anyBoolean())).thenReturn(false);
        when(visibilitySetService.getVisibleDocs(eq(kb.getId()), eq(7L), eq(false)))
                .thenReturn(VisibleDocSet.all());
    }

    private KnowledgeBase kb(Long id) {
        KnowledgeBase k = new KnowledgeBase();
        k.setId(id);
        k.setEmbeddingModel("doubao-embedding-vision");
        return k;
    }

    private RagRetrieveRequest req(Long kbId, String query) {
        RagRetrieveRequest r = new RagRetrieveRequest();
        r.setKbId(kbId);
        r.setQuery(query);
        return r;
    }
}
