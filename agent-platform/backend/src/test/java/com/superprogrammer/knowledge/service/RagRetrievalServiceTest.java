package com.superprogrammer.knowledge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.knowledge.config.AnswerCacheProperties;
import com.superprogrammer.knowledge.config.RagRecallProperties;
import com.superprogrammer.knowledge.dto.RagQueryRow;
import com.superprogrammer.knowledge.dto.RagRetrieveRequest;
import com.superprogrammer.knowledge.dto.RagRetrieveVO;
import com.superprogrammer.knowledge.entity.KnowledgeBase;
import com.superprogrammer.knowledge.mapper.RagRetrievalLogMapper;
import com.superprogrammer.knowledge.mapper.RagRetrievalQueryMapper;
import com.superprogrammer.knowledge.service.QueryExpansionService.ExpandedQuery;
import com.superprogrammer.knowledge.service.internal.AnswerCacheService;
import com.superprogrammer.knowledge.service.internal.CitationChecker;
import com.superprogrammer.knowledge.service.internal.VisibleDocSet;
import com.superprogrammer.llm.LlmGateway;
import com.superprogrammer.llm.dto.LlmResponse;
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
 * RagRetrievalService 检索 gate 测（mock mapper/LLM/可见集/缓存/扩展）。
 * 聚焦：权限 gate / NO_VISIBLE_DOCS / NO_DENSE_HITS / B4 单次扩展 / 软拒答 hard abstain + gray zone。
 */
@ExtendWith(MockitoExtension.class)
class RagRetrievalServiceTest {

    @Mock private RagRetrievalQueryMapper queryMapper;
    @Mock private RagRetrievalLogMapper logMapper;
    @Mock private KnowledgeBaseService knowledgeBaseService;
    @Mock private LlmGateway llmGateway;
    @Mock private VisibilitySetService visibilitySetService;
    @Mock private AnswerCacheService answerCacheService;
    @Mock private QueryExpansionService queryExpansionService;

    private final RagConfig ragConfig = new RagConfig();
    private final CitationChecker citationChecker = new CitationChecker();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AnswerCacheProperties answerCacheProps = new AnswerCacheProperties();  // 默认 enabled=false
    private final RagRecallProperties recallProps = new RagRecallProperties();           // 默认 hard=0.30 soft=0.45

    private RagRetrievalService service;

    @BeforeEach
    void setUp() {
        service = new RagRetrievalService(queryMapper, logMapper, knowledgeBaseService, llmGateway,
                ragConfig, citationChecker, objectMapper, visibilitySetService,
                answerCacheService, answerCacheProps, queryExpansionService, recallProps);
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
                .thenReturn(VisibleDocSet.of(java.util.Collections.emptySet()));

        RagRetrieveVO vo = service.retrieve(req(1L, "q"), 7L);

        assertTrue(vo.isAbstained());
        assertEquals("NO_VISIBLE_DOCS", vo.getAbstainReason());
        verify(queryExpansionService, never()).expand(anyString(), anyString(), any());  // 早 abstain，未扩展
    }

    @Test
    void noDenseHits_abstains() {
        KnowledgeBase kb = kb(1L);
        stubReadableAll(kb);
        stubExpandSingle();
        when(queryMapper.denseRecallL0(anyLong(), anyString(), anyBoolean(), anyList(), any(), anyInt()))
                .thenReturn(List.of());  // step5 空召回

        RagRetrieveVO vo = service.retrieve(req(1L, "如何安装"), 7L);

        assertTrue(vo.isAbstained());
        assertEquals("NO_DENSE_HITS", vo.getAbstainReason());
    }

    @Test
    void b4_queryExpandedExactlyOnce_embedDelegatedToExpansion() {
        KnowledgeBase kb = kb(1L);
        stubReadableAll(kb);
        stubExpandSingle();
        when(queryMapper.denseRecallL0(anyLong(), anyString(), anyBoolean(), anyList(), any(), anyInt()))
                .thenReturn(List.of());

        service.retrieve(req(1L, "如何安装"), 7L);

        // B4 精神：每个逻辑 query 一轮扩展；service 不再直调 embed（embed 在 QueryExpansionService 内）
        verify(queryExpansionService, times(1)).expand(anyString(), anyString(), any());
        verify(llmGateway, never()).embed(anyString(), anyString());
    }

    @Test
    void a2_hardAbstain_belowHardThreshold() {
        KnowledgeBase kb = kb(1L);
        stubReadableAll(kb);
        stubExpandSingle();
        // dense 命中 1 个 L0，距离 0.8 → sim 0.2（< hard 0.30）→ abstain
        RagQueryRow.DenseRecallRow dense = denseRow(10L, 99L, "安装步骤", 0.8);
        when(queryMapper.denseRecallL0(anyLong(), anyString(), anyBoolean(), anyList(), any(), anyInt()))
                .thenReturn(List.of(dense));
        when(queryMapper.fetchL2Children(anyLong(), anyList(), anyList()))
                .thenReturn(List.of(l2Row(11L, 99L, 10L, "安装步骤", "PostgreSQL16/pgvector", "hash11")));
        when(queryMapper.bm25HitsJieba(anyLong(), anyString(), anyList())).thenReturn(List.of());

        RagRetrieveVO vo = service.retrieve(req(1L, "如何安装"), 7L);

        assertTrue(vo.isAbstained());
        assertEquals("LOW_CONFIDENCE", vo.getAbstainReason());
        verify(llmGateway, never()).chat(any(), any());  // abstain 不生成
    }

    @Test
    void grayZone_betweenHardSoft_answersWithLowConfidence() {
        KnowledgeBase kb = kb(1L);
        stubReadableAll(kb);
        stubExpandSingle();
        // 距离 0.6 → sim 0.40 ∈ [hard 0.30, soft 0.45) → 灰区回答 + lowConfidence
        RagQueryRow.DenseRecallRow dense = denseRow(10L, 99L, "安装步骤", 0.6);
        when(queryMapper.denseRecallL0(anyLong(), anyString(), anyBoolean(), anyList(), any(), anyInt()))
                .thenReturn(List.of(dense));
        when(queryMapper.fetchL2Children(anyLong(), anyList(), anyList()))
                .thenReturn(List.of(l2Row(11L, 99L, 10L, "安装步骤", "PostgreSQL16 安装", "hash11")));
        when(queryMapper.bm25HitsJieba(anyLong(), anyString(), anyList())).thenReturn(List.of());
        when(queryMapper.reverifyNode(eq(11L))).thenReturn(hashRow("hash11"));
        // generate 调 chat(req, userId) 两参重载
        when(llmGateway.chat(any(), anyLong())).thenReturn(LlmResponse.builder().content("[1] 安装步骤说明").build());

        RagRetrieveVO vo = service.retrieve(req(1L, "如何安装"), 7L);

        assertFalse(vo.isAbstained());
        assertTrue(vo.isLowConfidence());   // 灰区标低置信
        assertNotNull(vo.getAnswer());
    }

    // ============================ helpers ============================

    /** 扩展 mock：返回单规范 halfvec（非空，让流程进入 step5）。 */
    private void stubExpandSingle() {
        when(queryExpansionService.expand(anyString(), anyString(), any()))
                .thenReturn(new ExpandedQuery("q", List.of("[0.1]")));
    }

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

    private RagQueryRow.DenseRecallRow denseRow(Long nodeId, Long docId, String title, double distance) {
        RagQueryRow.DenseRecallRow r = new RagQueryRow.DenseRecallRow();
        r.setNodeId(nodeId);
        r.setDocumentId(docId);
        r.setTitle(title);
        r.setCosineDistance(distance);
        return r;
    }

    private RagQueryRow.L2Row l2Row(Long nodeId, Long docId, Long parentId, String title, String content, String hash) {
        RagQueryRow.L2Row r = new RagQueryRow.L2Row();
        r.setNodeId(nodeId);
        r.setDocumentId(docId);
        r.setParentId(parentId);
        r.setTitle(title);
        r.setContent(content);
        r.setContentHash(hash);
        return r;
    }

    private RagQueryRow.HashVerifyRow hashRow(String hash) {
        RagQueryRow.HashVerifyRow r = new RagQueryRow.HashVerifyRow();
        r.setNodeHash(hash);
        r.setEmbedHash(hash);
        return r;
    }
}
