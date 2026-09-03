package com.superprogrammer.knowledge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.knowledge.config.AnswerCacheProperties;
import com.superprogrammer.knowledge.config.RagRecallProperties;
import com.superprogrammer.knowledge.dto.RagRetrieveRequest;
import com.superprogrammer.knowledge.dto.RagRetrieveVO;
import com.superprogrammer.knowledge.entity.KnowledgeBase;
import com.superprogrammer.knowledge.mapper.RagRetrievalLogMapper;
import com.superprogrammer.knowledge.mapper.RagRetrievalQueryMapper;
import com.superprogrammer.knowledge.service.internal.AnswerCacheService;
import com.superprogrammer.knowledge.service.internal.VisibleDocSet;
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
 * 14x#3 · spec §5.3 行4：POST /retrieve 检索调试对保密库成员整接口 403（防证据/原文旁路）；
 * owner 直通不受限。问答路径（/ask、chat kbIds → retrieveGroundedAnswer→retrieveEvidence）不接 Guard=唯一出口，
 * 由 RagRetrievalServiceAnswerModelTest 与集成链路共同守护（retrieveEvidence 无 Guard 调用即设计使然）。
 */
@ExtendWith(MockitoExtension.class)
class RagRetrieveConfidentialTest {

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

    private static final Long OWNER = 7L;
    private static final Long MEMBER = 8L;

    @BeforeEach
    void setUp() {
        service = new RagRetrievalService(queryMapper, logMapper, knowledgeBaseService, llmGateway,
                ragConfig, citationChecker, objectMapper, visibilitySetService,
                answerCacheService, answerCacheProps, queryExpansionService, recallProps,
                ragTraceService, rankingConfigService, queryPlanner, rankingEngine, productionRetrievalGateway,
                relationGraphPostProcessor, documentMapper, attachmentContentInjector,
                iterativeRetrievalOrchestrator, retrievalProps,
                evidencePolicyService, groundedAnswerService, ragRolloutService, ragShadowCoordinator);
        lenient().when(relationGraphPostProcessor.planExpansion(anyLong(), any(), anyBoolean(), any(), anyInt()))
                .thenReturn(new com.superprogrammer.knowledge.relation.RelationGraphPostProcessor.ExpansionPlan(
                        List.of(), List.of(), List.of(), 0, 0));
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
    }

    private KnowledgeBase confidentialKb() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(1L);
        kb.setEmbeddingModel("emb");
        kb.setConfidential(true);
        kb.setCreatedBy(OWNER);
        return kb;
    }

    private RagRetrieveRequest req() {
        RagRetrieveRequest r = new RagRetrieveRequest();
        r.setKbId(1L);
        r.setQuery("如何安装");
        return r;
    }

    @Test
    void retrieve_memberOfConfidentialKb_denied403() {
        KnowledgeBase kb = confidentialKb();
        when(knowledgeBaseService.ensure(1L)).thenReturn(kb);
        when(knowledgeBaseService.canRead(eq(kb), eq(MEMBER), anyBoolean())).thenReturn(true);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.retrieve(req(), MEMBER));
        assertEquals(ErrorCode.KNOWLEDGE_CONFIDENTIAL_DENIED.getCode(), ex.getCode());
        // 早 403：不触发查询扩展/召回（旁路零消耗）
        verifyNoInteractions(queryExpansionService, queryMapper);
    }

    @Test
    void retrieve_ownerPassesGuard_proceedsToRetrieval() {
        KnowledgeBase kb = confidentialKb();
        when(knowledgeBaseService.ensure(1L)).thenReturn(kb);
        when(knowledgeBaseService.canRead(eq(kb), eq(OWNER), anyBoolean())).thenReturn(true);
        // owner 直通后走正常检索流（此处可见集为空 → NO_VISIBLE_DOCS abstain，证明已越过保密门）
        when(visibilitySetService.getVisibleDocs(eq(1L), eq(OWNER), eq(false)))
                .thenReturn(VisibleDocSet.of(java.util.Collections.emptySet()));

        RagRetrieveVO vo = service.retrieve(req(), OWNER);

        assertTrue(vo.isAbstained());
        assertEquals("NO_VISIBLE_DOCS", vo.getAbstainReason());
    }
}
