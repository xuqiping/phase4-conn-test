package com.superprogrammer.knowledge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * WP2 Step5 · 基线回归门：合成黄金集 4 例，max-rounds=1（=基线行为）与 max-rounds=2（默认）
 * 两组全量跑——①无缺口场景 rounds=0 且证据集逐条一致（零回归）；②缺口场景差异仅允许
 * 「原缺失锚点现补齐」的正向变化（并集只增不丢）；③两组 Recall/MRR 指标输出落档
 * （数字记 plan 实现注+开发进度5；真实黄金集 DB 全量跑留 Phase4 实测）。
 */
@ExtendWith(MockitoExtension.class)
class RagBaselineRegressionGateTest {

    @Mock private RagRetrievalQueryMapper queryMapper;
    @Mock private RagRetrievalLogMapper logMapper;
    @Mock private KnowledgeBaseService knowledgeBaseService;
    @Mock private LlmGateway llmGateway;
    @Mock private VisibilitySetService visibilitySetService;
    @Mock private AnswerCacheService answerCacheService;
    @Mock private QueryExpansionService queryExpansionService;
    @Mock private RankingConfigService rankingConfigService;
    @Mock private com.superprogrammer.knowledge.query.QueryPlanner queryPlanner;
    @Mock private com.superprogrammer.knowledge.query.LlmQueryPlanner llmQueryPlanner;
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
    @Mock private com.superprogrammer.knowledge.global.GlobalAnswerStrategy globalAnswerStrategy;
    @Mock private com.superprogrammer.knowledge.trace.RagTraceService ragTraceService;
    @Mock private com.superprogrammer.knowledge.trace.RagTraceService.RetrievalScope retrievalScope;
    @Mock private com.superprogrammer.knowledge.trace.RagTraceService.RankingScope rankingScope;
    @Mock private com.superprogrammer.knowledge.migration.RagRolloutService ragRolloutService;
    @Mock private com.superprogrammer.knowledge.retrieval.RagShadowCoordinator ragShadowCoordinator;

    private final RagConfig ragConfig = new RagConfig();
    private final CitationChecker citationChecker = new CitationChecker();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AnswerCacheProperties answerCacheProps = new AnswerCacheProperties();
    private final RagRecallProperties recallProps = new RagRecallProperties();

    private RagRetrievalService service;

    /** 黄金集案例：名称/query/期望证据 nodeId 集（C3 期望补充轮锚点文档 21）。 */
    private record Case(String name, String query, Set<Long> expected) {}
    private final List<Case> goldenSet = List.of(
            new Case("C1-SEMANTIC-无filter", "如何安装", Set.of(11L, 31L, 41L)),
            new Case("C2-EXACT-round0已覆盖", "差旅制度 V2.1", Set.of(11L, 31L, 41L)),
            new Case("C3-EXACT-缺口需补轮", "报销流程 V3.0", Set.of(21L)),
            new Case("C4-LIST-无filter", "列出所有流程", Set.of(11L, 31L, 41L)));

    @BeforeEach
    void setUp() {
        service = new RagRetrievalService(queryMapper, logMapper, knowledgeBaseService, llmGateway,
                ragConfig, citationChecker, objectMapper, visibilitySetService,
                answerCacheService, answerCacheProps, queryExpansionService, recallProps,
                ragTraceService, rankingConfigService, queryPlanner, llmQueryPlanner, rankingEngine, productionRetrievalGateway,
                relationGraphPostProcessor, documentMapper, attachmentContentInjector,
                iterativeRetrievalOrchestrator, retrievalProps,
                evidencePolicyService, groundedAnswerService, globalAnswerStrategy, ragRolloutService, ragShadowCoordinator);

        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(1L);
        kb.setEmbeddingModel("doubao-embedding-vision");
        lenient().when(knowledgeBaseService.ensure(1L)).thenReturn(kb);
        lenient().when(knowledgeBaseService.canRead(eq(kb), eq(7L), anyBoolean())).thenReturn(true);
        lenient().when(knowledgeBaseService.canManage(eq(kb), eq(7L), anyBoolean())).thenReturn(false);
        lenient().when(visibilitySetService.getVisibleDocs(eq(1L), eq(7L), eq(false)))
                .thenReturn(VisibleDocSet.all());
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
        lenient().when(rankingEngine.rank(anyString(), anyString(), anyList(), any())).thenAnswer(invocation -> {
            List<com.superprogrammer.knowledge.retrieval.RetrievalCandidate> candidates = invocation.getArgument(2);
            return candidates.stream().map(c -> new com.superprogrammer.knowledge.ranking.RankingResult(
                    c.id(), c.rawScore(), invocation.getArgument(0), invocation.getArgument(3))).toList();
        });
        lenient().when(relationGraphPostProcessor.planExpansion(anyLong(), any(), anyBoolean(), any(), anyInt()))
                .thenReturn(new com.superprogrammer.knowledge.relation.RelationGraphPostProcessor.ExpansionPlan(
                        List.of(), List.of(), List.of(), 0, 0));
        lenient().when(evidencePolicyService.apply(anyString(), anyInt(), anyList(), anyInt(), anyDouble(), anyBoolean()))
                .thenAnswer(invocation -> new com.superprogrammer.knowledge.context.EvidencePolicyService.PolicyResult(
                        invocation.getArgument(2), "SUPPORTED"));

        // ---- 黄金集规划桩：C1/C4 无 filter（required 空）、C2 EXACT V2.1（round0 已覆盖）、C3 EXACT V3.0（缺口）----
        lenient().when(queryPlanner.plan(anyString())).thenReturn(new com.superprogrammer.knowledge.query.QueryPlan(
                "SEMANTIC", "DIRECT", java.util.Map.of(), List.of("DENSE", "SPARSE"), false, false, true));
        lenient().when(queryPlanner.plan("差旅制度 V2.1")).thenReturn(new com.superprogrammer.knowledge.query.QueryPlan(
                "EXACT", "DIRECT", java.util.Map.of("version", "V2.1"), List.of("SPARSE", "DENSE"),
                false, false, false));
        lenient().when(queryPlanner.plan("报销流程 V3.0")).thenReturn(new com.superprogrammer.knowledge.query.QueryPlan(
                "EXACT", "DIRECT", java.util.Map.of("version", "V3.0"), List.of("SPARSE", "DENSE"),
                false, false, false));
        lenient().when(queryPlanner.plan("列出所有流程")).thenReturn(new com.superprogrammer.knowledge.query.QueryPlan(
                "LIST", "LIST", java.util.Map.of(), List.of("SPARSE", "DENSE"), true, false, false));
        lenient().when(llmQueryPlanner.planWithFallback(anyString(), any())).thenAnswer(inv ->
                new com.superprogrammer.knowledge.query.LlmQueryPlanner.PlanOutcome(
                        queryPlanner.plan(inv.getArgument(0)), List.of(), false));

        // ---- 召回数据：round0 半向量 [0.1] 三文档；补充 query "V3.0" 半向量 [0.9] 命中锚点文档 ----
        lenient().when(queryExpansionService.expand(anyString(), anyString(), any(), anyBoolean()))
                .thenReturn(new ExpandedQuery("q", List.of("[0.1]")));
        lenient().when(queryExpansionService.expand(eq("V3.0"), anyString(), any(), anyBoolean()))
                .thenReturn(new ExpandedQuery("V3.0", List.of("[0.9]")));
        lenient().when(queryMapper.denseRecallL0(anyLong(), eq("[0.1]"), anyBoolean(), anyList(), any(), anyInt()))
                .thenReturn(List.of(denseRow(10L, 99L, "报销手册", 0.5),
                        denseRow(30L, 97L, "差旅制度 V2.1", 0.45),
                        denseRow(40L, 96L, "流程清单", 0.55)));
        lenient().when(queryMapper.denseRecallL0(anyLong(), eq("[0.9]"), anyBoolean(), anyList(), any(), anyInt()))
                .thenReturn(List.of(denseRow(20L, 98L, "报销流程 V3.0", 0.4)));
        lenient().when(queryMapper.fetchL2Children(anyLong(), anyList(), anyList())).thenAnswer(inv -> {
            List<Long> docIds = inv.getArgument(2);
            if (docIds.contains(98L)) {
                return List.of(l2Row(21L, 98L, 20L, "报销流程 V3.0", "V3.0 版报销流程原文", "hash21"));
            }
            return List.of(
                    l2Row(11L, 99L, 10L, "报销手册", "通用报销条款说明", "hash11"),
                    l2Row(31L, 97L, 30L, "差旅制度 V2.1", "V2.1 版差旅条款原文", "hash31"),
                    l2Row(41L, 96L, 40L, "流程清单", "全部流程清单说明", "hash41"));
        });
        lenient().when(queryMapper.bm25HitsJieba(anyLong(), anyString(), anyList())).thenReturn(List.of());
        lenient().when(queryMapper.reverifyNode(eq(11L))).thenReturn(hashRow("hash11"));
        lenient().when(queryMapper.reverifyNode(eq(21L))).thenReturn(hashRow("hash21"));
        lenient().when(queryMapper.reverifyNode(eq(31L))).thenReturn(hashRow("hash31"));
        lenient().when(queryMapper.reverifyNode(eq(41L))).thenReturn(hashRow("hash41"));
    }

    private RagRetrieveVO run(String query, int maxRounds) {
        retrievalProps.setMaxRounds(maxRounds);
        return service.retrieve(req(query), 7L);
    }

    private RagRetrieveRequest req(String query) {
        RagRetrieveRequest r = new RagRetrieveRequest();
        r.setKbId(1L);
        r.setQuery(query);
        return r;
    }

    /** 证据指纹（nodeId:content 逐条），两组比对=「证据集逐条一致」断言载体。 */
    private static List<String> fingerprint(RagRetrieveVO vo) {
        return vo.getEvidenceL2().stream()
                .map(e -> e.getNodeId() + ":" + e.getContent()).toList();
    }

    @Test
    void zeroGapCases_rounds0_evidenceIdenticalAcrossConfigs() {
        for (String query : List.of("如何安装", "差旅制度 V2.1", "列出所有流程")) {
            RagRetrieveVO a = run(query, 1);   // 基线行为
            RagRetrieveVO b = run(query, 2);   // 默认配置

            assertEquals(0, a.getTokenBudget().getRounds(), query + " maxRounds=1 须零补充轮");
            assertEquals(0, b.getTokenBudget().getRounds(), query + " 无缺口 maxRounds=2 也不许补轮");
            assertFalse(a.getEvidenceL2().isEmpty(), query + " 基线证据非空");
            assertEquals(fingerprint(a), fingerprint(b), query + " 证据集须逐条一致（零回归门）");
        }
    }

    @Test
    void gapCase_rounds1OnlyAtMaxRounds2_positiveOnlyDiff() {
        RagRetrieveVO a = run("报销流程 V3.0", 1);
        RagRetrieveVO b = run("报销流程 V3.0", 2);

        assertEquals(0, a.getTokenBudget().getRounds());
        assertTrue(a.getEvidenceL2().stream().noneMatch(e -> e.getNodeId() == 21L),
                "基线组锚点文档缺失（=原 INSUFFICIENT 场景）");
        assertEquals(1, b.getTokenBudget().getRounds());
        assertTrue(b.getEvidenceL2().stream().anyMatch(e -> e.getNodeId() == 21L),
                "默认组补轮召回 V3.0 锚点文档");
        // 差异正向性：topK 窗口固定（B3 候选上限），补轮候选按统一重排分数竞争进窗——
        // 允许挤掉基线组**低分尾**证据，但挤掉者分数必须严格高于被挤者（不允许低分挤高分）
        Set<Long> aNodes = new LinkedHashSet<>();
        a.getEvidenceL2().forEach(e -> aNodes.add(e.getNodeId()));
        Set<Long> bNodes = new LinkedHashSet<>();
        b.getEvidenceL2().forEach(e -> bNodes.add(e.getNodeId()));
        assertEquals(aNodes.size(), bNodes.size(), "证据窗口大小不变");
        Set<Long> lost = new LinkedHashSet<>(aNodes);
        lost.removeAll(bNodes);
        Set<Long> gained = new LinkedHashSet<>(bNodes);
        gained.removeAll(aNodes);
        java.util.Map<Long, Double> sim = java.util.Map.of(21L, 0.6, 31L, 0.55, 11L, 0.5, 41L, 0.45);
        for (Long l : lost) {
            for (Long g : gained) {
                assertTrue(sim.get(g) > sim.get(l),
                        "挤掉者(" + g + ",sim=" + sim.get(g) + ")分须高于被挤者(" + l + ",sim=" + sim.get(l) + ")");
            }
        }
    }

    @Test
    void goldenSetMetrics_recallMrrNonDecreasing() {
        double[] recall = new double[2], mrr = new double[2];
        for (int g = 0; g < 2; g++) {
            int maxRounds = g + 1;
            double rSum = 0, mSum = 0;
            for (Case c : goldenSet) {
                List<Long> hits = run(c.query(), maxRounds).getEvidenceL2().stream()
                        .map(RagRetrieveVO.EvidenceVO::getNodeId).toList();
                long hit = c.expected().stream().filter(hits::contains).count();
                rSum += (double) hit / c.expected().size();
                mSum += hits.stream().filter(c.expected()::contains).findFirst()
                        .map(h -> 1.0 / (hits.indexOf(h) + 1)).orElse(0.0);
            }
            recall[g] = rSum / goldenSet.size();
            mrr[g] = mSum / goldenSet.size();
        }
        // 指标输出落档（数字同步记 plan 实现注+开发进度5）
        System.out.printf("[基线回归门] 黄金集4例 maxRounds=1: Recall=%.3f MRR=%.3f | maxRounds=2: Recall=%.3f MRR=%.3f%n",
                recall[0], mrr[0], recall[1], mrr[1]);
        assertTrue(recall[1] >= recall[0], "默认配置 Recall 不得低于基线");
        assertTrue(mrr[1] >= mrr[0], "默认配置 MRR 不得低于基线");
        assertTrue(recall[1] > recall[0], "缺口案例存在时默认组 Recall 须严格改善");
        assertEquals(1.0, recall[1], 1e-9, "默认组黄金集全命中");
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

    private RagQueryRow.HashVerifyRow hashRow(String hash) {
        RagQueryRow.HashVerifyRow r = new RagQueryRow.HashVerifyRow();
        r.setNodeHash(hash);
        r.setEmbedHash(hash);
        r.setMetadata("{\"titlePath\":[\"报销\"],\"locator\":{\"pageStart\":3,\"pageEnd\":3}}");
        return r;
    }
}
