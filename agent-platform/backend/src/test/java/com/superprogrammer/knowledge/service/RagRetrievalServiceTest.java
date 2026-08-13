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
    @Mock private RankingConfigService rankingConfigService;
    @Mock private com.superprogrammer.knowledge.query.QueryPlanner queryPlanner;
    @Mock private com.superprogrammer.knowledge.ranking.RankingEngine rankingEngine;
    @Mock private com.superprogrammer.knowledge.retrieval.ProductionRetrievalGateway productionRetrievalGateway;
    @Mock private com.superprogrammer.knowledge.context.EvidencePolicyService evidencePolicyService;
    @Mock private com.superprogrammer.knowledge.trace.RagTraceService ragTraceService;
    @Mock private com.superprogrammer.knowledge.trace.RagTraceService.RetrievalScope retrievalScope;
    @Mock private com.superprogrammer.knowledge.trace.RagTraceService.RankingScope rankingScope;

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
                answerCacheService, answerCacheProps, queryExpansionService, recallProps,
                ragTraceService, rankingConfigService, queryPlanner, rankingEngine, productionRetrievalGateway,
                evidencePolicyService);
        lenient().when(ragTraceService.beginRetrieval(anyList(), anyString(), any(), anyString()))
                .thenReturn(retrievalScope);
        lenient().when(rankingConfigService.resolve(anyLong())).thenReturn(
                new RankingConfigService.ResolvedRankingConfig(null, "DISABLED", null, "test-disabled",
                        30, 10, 10, 4000, "FAIL_CLOSED", false,
                        RankingConfigService.Source.ADMIN_DEFAULT));
        lenient().when(ragTraceService.beginRanking(anyString(), anyString(), any(), anyString(),
                anyInt(), anyString(), nullable(String.class))).thenReturn(rankingScope);
        lenient().when(queryPlanner.plan(anyString())).thenReturn(new com.superprogrammer.knowledge.query.QueryPlan(
                "SEMANTIC", "DIRECT", java.util.Map.of(), List.of("DENSE", "SPARSE"), false, false, true));
        lenient().when(rankingEngine.rank(anyString(), anyString(), anyList(), any())).thenAnswer(invocation -> {
            List<com.superprogrammer.knowledge.retrieval.RetrievalCandidate> candidates = invocation.getArgument(2);
            return candidates.stream().map(c -> new com.superprogrammer.knowledge.ranking.RankingResult(
                    c.id(), c.rawScore(), invocation.getArgument(0), invocation.getArgument(3))).toList();
        });
        lenient().when(evidencePolicyService.apply(anyString(), anyInt(), anyList(), anyInt(), anyDouble(), anyBoolean()))
                .thenAnswer(invocation -> new com.superprogrammer.knowledge.context.EvidencePolicyService.PolicyResult(
                        invocation.getArgument(2), "SUPPORTED"));
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
        verify(queryExpansionService, never()).expand(anyString(), anyString(), any(), anyBoolean());  // 早 abstain，未扩展
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
    void exactPlanSkipsRewriteAndUsesPrefilteredProductionRetrieval() {
        KnowledgeBase kb = kb(1L);
        stubReadableAll(kb);
        when(queryPlanner.plan("请找第十条 V2.1")).thenReturn(new com.superprogrammer.knowledge.query.QueryPlan(
                "EXACT", "DIRECT", java.util.Map.of("version", "V2.1"), List.of("EXACT", "SPARSE"),
                false, false, false));
        when(queryExpansionService.expand(eq("请找第十条 V2.1"), anyString(), eq(7L), eq(false)))
                .thenReturn(new ExpandedQuery("请找第十条 V2.1", List.of("[0.1]")));
        when(queryMapper.denseRecallL0(anyLong(), anyString(), anyBoolean(), anyList(), any(), anyInt()))
                .thenReturn(List.of());
        when(productionRetrievalGateway.retrieve(eq("请找第十条 V2.1"), any(),
                eq(List.of("EXACT", "SPARSE")), anyInt())).thenReturn(List.of());

        service.retrieve(req(1L, "请找第十条 V2.1"), 7L);

        verify(queryExpansionService).expand("请找第十条 V2.1", kb.getEmbeddingModel(), 7L, false);
        verify(productionRetrievalGateway).retrieve(eq("请找第十条 V2.1"), argThat(filter ->
                filter.summary().contains("kb=1") && filter.summary().contains("status=ACTIVE")),
                eq(List.of("EXACT", "SPARSE")), anyInt());
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
        verify(queryExpansionService, times(1)).expand(anyString(), anyString(), any(), anyBoolean());
        verify(llmGateway, never()).embed(anyString(), anyString());
        verify(queryPlanner).plan("如何安装");
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
        when(queryPlanner.plan(anyString())).thenReturn(new com.superprogrammer.knowledge.query.QueryPlan(
                "PROCEDURE", "ORDERED_STEPS", java.util.Map.of(),
                List.of("SPARSE", "DENSE", "NEIGHBOR"), true, true, false));
        // 距离 0.6 → sim 0.40 ∈ [hard 0.30, soft 0.45) → 灰区回答 + lowConfidence
        RagQueryRow.DenseRecallRow dense = denseRow(10L, 99L, "安装步骤", 0.6);
        when(queryMapper.denseRecallL0(anyLong(), anyString(), anyBoolean(), anyList(), any(), anyInt()))
                .thenReturn(List.of(dense));
        when(queryMapper.fetchL2Children(anyLong(), anyList(), anyList()))
                .thenReturn(List.of(l2Row(11L, 99L, 10L, "安装步骤", "PostgreSQL16 安装", "hash11")));
        when(queryMapper.bm25HitsJieba(anyLong(), anyString(), anyList())).thenReturn(List.of());
        when(queryMapper.reverifyNode(eq(11L))).thenReturn(hashRow("hash11"));
        RagRetrieveRequest request = req(1L, "如何安装");
        request.setGenerateAnswer(true);
        // generate 调 chat(req, userId) 两参重载（lenient：流程短路时不触达，防误报）
        lenient().when(llmGateway.chat(any(), any())).thenReturn(LlmResponse.builder().content("[1] 安装步骤说明").build());

        RagRetrieveVO vo = service.retrieve(request, 7L);

        assertFalse(vo.isAbstained());
        assertTrue(vo.isLowConfidence());   // 灰区标低置信
        assertNotNull(vo.getAnswer());
        verify(evidencePolicyService).apply(eq("PROCEDURE"), anyInt(), anyList(), anyInt(), anyDouble(), eq(false));
        assertEquals("SUPPORTED", vo.getConfidenceState());
        assertFalse(vo.getCitations().isEmpty(), vo.toString());
        assertEquals("3", vo.getCitations().get(0).getPage());
        assertEquals("安装", vo.getCitations().get(0).getArticle());
    }

    @Test
    void configuredRankingEngineActuallyReordersCandidates() {
        KnowledgeBase kb = kb(1L);
        stubReadableAll(kb);
        stubExpandSingle();
        when(rankingConfigService.resolve(1L)).thenReturn(new RankingConfigService.ResolvedRankingConfig(
                12L, "LLM", "ranking-chat", "rc-1", 30, 10, 10, 4000,
                "FALLBACK_RRF", false, RankingConfigService.Source.KNOWLEDGE_BASE));
        when(queryMapper.denseRecallL0(anyLong(), anyString(), anyBoolean(), anyList(), any(), anyInt()))
                .thenReturn(List.of(denseRow(10L, 99L, "安装步骤", 0.6)));
        when(queryMapper.fetchL2Children(anyLong(), anyList(), anyList()))
                .thenReturn(List.of(l2Row(11L, 99L, 10L, "安装步骤", "PostgreSQL16 安装", "hash11")));
        when(queryMapper.bm25HitsJieba(anyLong(), anyString(), anyList())).thenReturn(List.of());
        when(queryMapper.reverifyNode(11L)).thenReturn(hashRow("hash11"));
        when(ragTraceService.beginRanking(eq("LLM"), eq("LLM"), eq(12L), eq("rc-1"),
                eq(1), anyString(), isNull())).thenReturn(rankingScope);
        when(rankingEngine.rank(eq("LLM"), eq("如何安装"), anyList(), eq("ranking-chat")))
                .thenReturn(List.of(new com.superprogrammer.knowledge.ranking.RankingResult(
                        "11", 0.99, "LLM", "ranking-chat")));
        lenient().when(llmGateway.chat(any(), any()))
                .thenReturn(LlmResponse.builder().content("[1] 安装步骤说明").build());

        RagRetrieveVO vo = service.retrieve(req(1L, "如何安装"), 7L);

        assertFalse(vo.isAbstained());
        verify(rankingScope).succeed(1);
        verify(rankingEngine).rank(eq("LLM"), eq("如何安装"), anyList(), eq("ranking-chat"));
        verify(rankingEngine).rank(eq("LLM"), eq("如何安装"), argThat(candidates ->
                candidates.size() == 1 && "PostgreSQL16 安装".equals(candidates.get(0).content())), eq("ranking-chat"));
    }

    // ============================ helpers ============================

    /** 扩展 mock：返回单规范 halfvec（非空，让流程进入 step5）。 */
    private void stubExpandSingle() {
        when(queryExpansionService.expand(anyString(), anyString(), any(), anyBoolean()))
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
        r.setMetadata("{\"titlePath\":[\"安装\"],\"locator\":{\"pageStart\":3,\"pageEnd\":3}}");
        return r;
    }
}
