package com.superprogrammer.knowledge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.knowledge.config.AnswerCacheProperties;
import com.superprogrammer.knowledge.config.RagRecallProperties;
import com.superprogrammer.knowledge.dto.RagQueryRow;
import com.superprogrammer.knowledge.dto.RagRetrieveRequest;
import com.superprogrammer.knowledge.entity.KnowledgeBase;
import com.superprogrammer.knowledge.mapper.RagRetrievalLogMapper;
import com.superprogrammer.knowledge.mapper.RagRetrievalQueryMapper;
import com.superprogrammer.knowledge.service.QueryExpansionService.ExpandedQuery;
import com.superprogrammer.knowledge.service.internal.AnswerCacheService;
import com.superprogrammer.knowledge.service.internal.VisibleDocSet;
import com.superprogrammer.llm.LlmGateway;
import com.superprogrammer.llm.dto.LlmRequest;
import com.superprogrammer.llm.dto.LlmResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 14x#1 · per-KB answer_model 透传：grounded 链路两处 LlmRequest（事实提炼/答案合成）
 * 有值显式 set，空值不 set（LlmGateway 走全局默认回退）。
 */
@ExtendWith(MockitoExtension.class)
class RagRetrievalServiceAnswerModelTest {

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
    @Mock private com.superprogrammer.knowledge.relation.RelationGraphPostProcessor relationGraphPostProcessor;
    @Mock private com.superprogrammer.knowledge.mapper.KnowledgeDocumentMapper documentMapper;
    @Mock private com.superprogrammer.knowledge.attachment.AttachmentContentInjector attachmentContentInjector;
    private final com.superprogrammer.knowledge.retrieval.IterativeRetrievalOrchestrator iterativeRetrievalOrchestrator =
            new com.superprogrammer.knowledge.retrieval.IterativeRetrievalOrchestrator();
    private final com.superprogrammer.knowledge.config.RagRetrievalProperties retrievalProps =
            new com.superprogrammer.knowledge.config.RagRetrievalProperties();
    @Mock private com.superprogrammer.knowledge.context.EvidencePolicyService evidencePolicyService;
    @Mock private com.superprogrammer.knowledge.answer.GroundedAnswerService groundedAnswerService;
    @Mock private com.superprogrammer.knowledge.trace.RagTraceService ragTraceService;
    @Mock private com.superprogrammer.knowledge.trace.RagTraceService.RetrievalScope retrievalScope;
    @Mock private com.superprogrammer.knowledge.trace.RagTraceService.RankingScope rankingScope;
    @Mock private com.superprogrammer.knowledge.migration.RagRolloutService ragRolloutService;
    @Mock private com.superprogrammer.knowledge.retrieval.RagShadowCoordinator ragShadowCoordinator;

    private final RagConfig ragConfig = new RagConfig();
    private final com.superprogrammer.knowledge.service.internal.CitationChecker citationChecker =
            new com.superprogrammer.knowledge.service.internal.CitationChecker();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AnswerCacheProperties answerCacheProps = new AnswerCacheProperties();
    private final RagRecallProperties recallProps = new RagRecallProperties();

    private RagRetrievalService service;

    @BeforeEach
    void setUp() {
        service = new RagRetrievalService(queryMapper, logMapper, knowledgeBaseService, llmGateway,
                ragConfig, citationChecker, objectMapper, visibilitySetService,
                answerCacheService, answerCacheProps, queryExpansionService, recallProps,
                ragTraceService, rankingConfigService, queryPlanner, rankingEngine, productionRetrievalGateway,
                relationGraphPostProcessor, documentMapper, attachmentContentInjector,
                iterativeRetrievalOrchestrator, retrievalProps,
                evidencePolicyService, groundedAnswerService, ragRolloutService, ragShadowCoordinator);
        lenient().when(ragRolloutService.status(anyLong())).thenAnswer(invocation ->
                new com.superprogrammer.knowledge.migration.RagRolloutService.RolloutState(
                        invocation.getArgument(0), 0, "champion", 0));
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
        lenient().when(relationGraphPostProcessor.planExpansion(anyLong(), any(), anyBoolean(), any(), anyInt()))
                .thenReturn(new com.superprogrammer.knowledge.relation.RelationGraphPostProcessor.ExpansionPlan(
                        List.of(), List.of(), List.of(), 0, 0));
        lenient().when(evidencePolicyService.apply(anyString(), anyInt(), anyList(), anyInt(), anyDouble(), anyBoolean()))
                .thenAnswer(invocation -> new com.superprogrammer.knowledge.context.EvidencePolicyService.PolicyResult(
                        invocation.getArgument(2), "SUPPORTED"));
        lenient().when(rankingEngine.rank(anyString(), anyString(), anyList(), any())).thenAnswer(invocation -> {
            List<com.superprogrammer.knowledge.retrieval.RetrievalCandidate> candidates = invocation.getArgument(2);
            return candidates.stream().map(c -> new com.superprogrammer.knowledge.ranking.RankingResult(
                    c.id(), c.rawScore(), invocation.getArgument(0), invocation.getArgument(3))).toList();
        });
        // 事实提炼 LLM 返回可解析 JSON 数组（extractGroundedFacts 二次兜底不触发）
        lenient().when(llmGateway.chat(any(), any())).thenReturn(
                LlmResponse.builder().content("[{\"subject\":\"安装\",\"value\":\"安装步骤说明\",\"citationIds\":[1]}]").build());
        // synthesize 透传 extractor：对单批证据真实调用（触发 extractGroundedFacts 的 LlmRequest），再返回既有事实
        lenient().when(groundedAnswerService.synthesize(anyList(), anyInt(), any())).thenAnswer(inv -> {
            java.util.function.Function<List<com.superprogrammer.knowledge.answer.GroundedAnswerService.Evidence>,
                    List<com.superprogrammer.knowledge.answer.GroundedAnswerService.Fact>> extractor = inv.getArgument(2);
            extractor.apply(List.of(new com.superprogrammer.knowledge.answer.GroundedAnswerService.Evidence(1, "安装步骤说明")));
            return new com.superprogrammer.knowledge.answer.GroundedAnswerService.Result(
                    List.of(new com.superprogrammer.knowledge.answer.GroundedAnswerService.Fact(
                            "安装", "安装步骤说明", List.of(1))), false);
        });
        lenient().when(groundedAnswerService.renderFacts(anyList())).thenReturn("安装：安装步骤说明 [1]");
    }

    /** 驱动 grounded 全链路（SUPPORTED 非灰区：距离 0.3 → sim 0.7 > soft 0.45）。 */
    private void stubGroundedPath(KnowledgeBase kb) {
        when(knowledgeBaseService.ensure(kb.getId())).thenReturn(kb);
        when(knowledgeBaseService.canRead(eq(kb), eq(7L), anyBoolean())).thenReturn(true);
        when(knowledgeBaseService.canManage(eq(kb), eq(7L), anyBoolean())).thenReturn(false);
        when(visibilitySetService.getVisibleDocs(eq(kb.getId()), eq(7L), eq(false)))
                .thenReturn(VisibleDocSet.all());
        when(queryExpansionService.expand(anyString(), anyString(), any(), anyBoolean()))
                .thenReturn(new ExpandedQuery("q", List.of("[0.1]")));
        when(queryMapper.denseRecallL0(anyLong(), anyString(), anyBoolean(), anyList(), any(), anyInt()))
                .thenReturn(List.of(denseRow(10L, 99L, "安装步骤", 0.3)));
        when(queryMapper.fetchL2Children(anyLong(), anyList(), anyList()))
                .thenReturn(List.of(l2Row(11L, 99L, 10L, "安装步骤", "PostgreSQL16 安装", "hash11")));
        when(queryMapper.bm25HitsJieba(anyLong(), anyString(), anyList())).thenReturn(List.of());
        when(queryMapper.reverifyNode(11L)).thenReturn(hashRow());
    }

    private List<LlmRequest> capturedChatRequests() {
        ArgumentCaptor<LlmRequest> captor = ArgumentCaptor.forClass(LlmRequest.class);
        verify(llmGateway, atLeast(2)).chat(captor.capture(), eq(7L));
        return captor.getAllValues();
    }

    @Test
    void answerModelSet_threadsIntoExtractAndComposeRequests() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(1L);
        kb.setEmbeddingModel("doubao-embedding-vision");
        stubGroundedPath(kb);
        when(knowledgeBaseService.resolveAnswerModel(kb)).thenReturn("glm-5.1");

        RagRetrieveRequest request = new RagRetrieveRequest();
        request.setKbId(1L);
        request.setQuery("如何安装");
        request.setGenerateAnswer(true);

        service.retrieve(request, 7L);

        List<LlmRequest> reqs = capturedChatRequests();
        LlmRequest extract = reqs.stream().filter(r -> "GROUNDING_FACT_EXTRACTION".equals(r.getCallPurpose())).findFirst().orElseThrow();
        LlmRequest compose = reqs.stream().filter(r -> "GROUNDED_ANSWER_COMPOSITION".equals(r.getCallPurpose())).findFirst().orElseThrow();
        assertEquals("glm-5.1", extract.getModel(), "事实提炼 LlmRequest 须带 per-KB 问答模型");
        assertEquals("glm-5.1", compose.getModel(), "答案合成 LlmRequest 须带 per-KB 问答模型");
    }

    @Test
    void answerModelNull_leavesModelUnset_forGlobalDefaultFallback() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(1L);
        kb.setEmbeddingModel("doubao-embedding-vision");
        stubGroundedPath(kb);
        // resolveAnswerModel 未 stub → 默认 null（=库未配置问答模型）

        RagRetrieveRequest request = new RagRetrieveRequest();
        request.setKbId(1L);
        request.setQuery("如何安装");
        request.setGenerateAnswer(true);

        service.retrieve(request, 7L);

        List<LlmRequest> reqs = capturedChatRequests();
        LlmRequest extract = reqs.stream().filter(r -> "GROUNDING_FACT_EXTRACTION".equals(r.getCallPurpose())).findFirst().orElseThrow();
        LlmRequest compose = reqs.stream().filter(r -> "GROUNDED_ANSWER_COMPOSITION".equals(r.getCallPurpose())).findFirst().orElseThrow();
        assertNull(extract.getModel(), "未配置模型不 set → LlmGateway 走管理员默认对话模型");
        assertNull(compose.getModel(), "未配置模型不 set → LlmGateway 走管理员默认对话模型");
    }

    // ============================ helpers ============================

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

    private RagQueryRow.HashVerifyRow hashRow() {
        RagQueryRow.HashVerifyRow r = new RagQueryRow.HashVerifyRow();
        r.setNodeHash("hash11");
        r.setEmbedHash("hash11");
        r.setMetadata("{\"titlePath\":[\"安装\"],\"locator\":{\"pageStart\":3,\"pageEnd\":3}}");
        return r;
    }
}
