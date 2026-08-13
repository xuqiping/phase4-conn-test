package com.superprogrammer.knowledge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.knowledge.dto.RagQueryRow;
import com.superprogrammer.knowledge.dto.RagRetrieveRequest;
import com.superprogrammer.knowledge.dto.RagRetrieveVO;
import com.superprogrammer.knowledge.entity.KnowledgeBase;
import com.superprogrammer.knowledge.entity.RagRetrievalLog;
import com.superprogrammer.knowledge.mapper.RagRetrievalLogMapper;
import com.superprogrammer.knowledge.mapper.RagRetrievalQueryMapper;
import com.superprogrammer.knowledge.config.AnswerCacheProperties;
import com.superprogrammer.knowledge.config.RagRecallProperties;
import com.superprogrammer.knowledge.dto.CachedPayload;
import com.superprogrammer.knowledge.service.internal.AnswerCacheService;
import com.superprogrammer.knowledge.service.internal.CitationChecker;
import com.superprogrammer.knowledge.service.internal.L1Metadata;
import com.superprogrammer.knowledge.service.internal.RrfFusion;
import com.superprogrammer.knowledge.service.internal.VisibleDocSet;
import com.superprogrammer.knowledge.util.TokenEstimator;
import com.superprogrammer.knowledge.trace.RagTraceService;
import com.superprogrammer.llm.LlmGateway;
import com.superprogrammer.llm.dto.LlmMessage;
import com.superprogrammer.llm.dto.LlmRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * RAG 检索核心（v6 §4 线性 8 步，§6.1 强制召回 WHERE 封装在 {@link RagRetrievalQueryMapper}）。
 * 业务层禁绕过 dense 召回 SQL（I1/P1 落该 SQL）。
 *
 * Phase1 偏离（DEV-*）：step2 缓存跳过；step4 目录降级全库；step6 rerank 用父L0 cosine 代理；
 * step1 可见集 USER 直接授权（admin/owner→全库）。其余 v6 §4 不变。
 *
 * 流程线性无循环：step1→3→4→5→6→7→8，每 gate 仅依赖此前输出。
 * 不变式：I1(dense SQL)/I3(evidence 复校)/P1(post-ANN SQL)/A1(Citation)/A2(abstain)/
 *        B1(effectiveContextCap)/B2(prompt≤cap)/B3(rerank pair≤100)/B4(单 query embed)/R1(L2 不嵌)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagRetrievalService {

    private static final Long TENANT_ID = 1L;
    private static final String IDENTITY_USER = "USER";
    private static final String DEFAULT_MODE = "BALANCED";
    private static final String ABSTAIN_MSG = "未找到可访问的相关知识。";

    private final RagRetrievalQueryMapper queryMapper;
    private final RagRetrievalLogMapper logMapper;
    private final KnowledgeBaseService knowledgeBaseService;
    private final LlmGateway llmGateway;
    private final RagConfig ragConfig;
    private final CitationChecker citationChecker;
    private final ObjectMapper objectMapper;
    private final VisibilitySetService visibilitySetService;
    private final AnswerCacheService answerCacheService;
    private final AnswerCacheProperties answerCacheProps;
    private final QueryExpansionService queryExpansionService;
    private final RagRecallProperties recallProps;
    private final RagTraceService ragTraceService;
    private final RankingConfigService rankingConfigService;
    private final com.superprogrammer.knowledge.query.QueryPlanner queryPlanner;
    private final com.superprogrammer.knowledge.ranking.RankingEngine rankingEngine;
    private final com.superprogrammer.knowledge.retrieval.ProductionRetrievalGateway productionRetrievalGateway;
    private final com.superprogrammer.knowledge.context.EvidencePolicyService evidencePolicyService;
    private final com.superprogrammer.knowledge.answer.GroundedAnswerService groundedAnswerService;
    private final com.superprogrammer.knowledge.citation.CitationVerifier citationVerifier =
            new com.superprogrammer.knowledge.citation.CitationVerifier();

    // ============================ 内部 record ============================

    private record VisibleSet(boolean allDocs, List<Long> docIds) {
    }

    private record RecallHit(Long nodeId, Long documentId, String title, String content,
                             double cosineDistance, double cosineSim) {
    }

    /** L1 文档召回命中（doc 级语义锚，Phase3）。 */
    private record L1DocHit(Long documentId, String title, double cosineDistance, double cosineSim) {
    }

    private record L2Candidate(Long nodeId, Long documentId, Long parentId, String title,
                               String content, String contentHash,
                               double parentL0Sim, Double bm25Rank, double rerankScore,
                               boolean bm25Only, double docL1Sim) {
    }

    private record Evidence(Long nodeId, Long documentId, String title, String content,
                            String contentHash, String docType,
                            String fileRef, String mime, String originalName,
                            String l1Outline, String l1Rules,
                            int citationIndex, double rerankScore, LocatorData locator) {
    }

    private record LocatorData(String canonical, String page, String article,
                               String sheet, String cellRange, String bbox) {
    }

    private record EvidencePack(String prompt, Set<Integer> injectedIndexes,
                                List<Evidence> injected) {
    }

    // ============================ 入口 ============================

    public RagRetrieveVO retrieve(RagRetrieveRequest req, Long userId) {
        try (var run = ragTraceService.beginRetrieval(List.of(req.getKbId()), req.getQuery(), userId, "RETRIEVE")) {
            try {
                RagRetrieveVO result = retrieveInternal(req, userId);
                if (result.isAbstained()) run.abstain(result.getAbstainReason());
                else run.succeed("SUPPORTED");
                return result;
            } catch (RuntimeException e) {
                run.fail(e instanceof BusinessException be ? String.valueOf(be.getCode()) : "INTERNAL_ERROR", e.getMessage());
                throw e;
            }
        }
    }

    private RagRetrieveVO retrieveInternal(RagRetrieveRequest req, Long userId) {
        long t0 = System.currentTimeMillis();
        String traceId = UUID.randomUUID().toString().replace("-", "");
        String mode = req.getMode() == null || req.getMode().isBlank() ? DEFAULT_MODE : req.getMode();
        int cap = ragConfig.computeEffectiveContextCap();

        RagRetrieveVO.TokenBudgetVO budget = RagRetrieveVO.TokenBudgetVO.builder()
                .maxContextTokens(ragConfig.getMaxContextTokens())
                .modelMaxContext(ragConfig.getModelMaxContext())
                .answerTokenReserve(ragConfig.getAnswerTokenReserve())
                .effectiveContextCap(cap)
                .promptTokens(0).build();

        RagRetrievalLog trace = newLog(traceId, userId, req, mode);
        try {
            KnowledgeBase kb = knowledgeBaseService.ensure(req.getKbId());
            com.superprogrammer.knowledge.query.QueryPlan queryPlan = queryPlanner.plan(req.getQuery());
            boolean admin = req.isAdminHint();
            if (!knowledgeBaseService.canRead(kb, userId, admin)) {
                throw new BusinessException(ErrorCode.FORBIDDEN, "无权访问该知识库");
            }
            String embedModel = kb.getEmbeddingModel();

            // step1 可见集
            VisibleSet vs = step1VisibleSet(kb, userId, admin);
            if (!vs.allDocs && vs.docIds.isEmpty()) {
                return finishAbstain(trace, budget, t0, "NO_VISIBLE_DOCS", req, List.of(), List.of(),
                        false, List.of(), List.of());
            }

            // B4：query 多路扩展（规范 query + 释义 + HyDE），返回 halfvec 列表（规范第一个）
            QueryExpansionService.ExpandedQuery eq = queryExpansionService.expand(
                    req.getQuery(), embedModel, userId, queryPlan.requiresLlmAnalysis());
            List<String> qHalfs = eq.qHalfs();
            if (qHalfs.isEmpty()) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "query embedding 失败");
            }
            String qHalf = qHalfs.get(0);   // 规范 query → 答案缓存键（D8）
            AnswerCacheService.CacheProtocol cacheProtocol = answerCacheProps.isEnabled()
                    ? answerCacheService.protocol(List.of(req.getKbId()), embedModel,
                            rankingConfigService.resolve(req.getKbId()).configVersion())
                    : null;

            // step2 答案缓存（阶段4-B）：命中则跳过 step3-8 + 生成，回放缓存 answer
            if (answerCacheProps.isEnabled()) {
                List<AnswerCacheService.KbScope> sigScopes = List.of(
                        new AnswerCacheService.KbScope(req.getKbId(), vs.allDocs, vs.docIds));
                String cacheSig = answerCacheService.permissionSignature(sigScopes);
                Optional<CachedPayload> hit = answerCacheService.lookup(qHalf, userId, cacheSig, cacheProtocol);
                if (hit.isPresent()) {
                    CachedPayload p = hit.get();
                    budget.setPromptTokens(0);
                    RagRetrieveVO vo = RagRetrieveVO.builder()
                            .traceId(traceId).abstained(false).abstainReason(null)
                            .answer(p.getAnswer())
                            .citations(toCitationVOs(p.getCitations()))
                            .candidatesL0(List.of()).candidatesL1(List.of())
                            .bm25Fallback(false).candidatesBm25(List.of())
                            .evidenceL2(List.of())
                            .tokenBudget(budget).latencyMs(System.currentTimeMillis() - t0).build();
                    trace.setL2LexicalFallback(false);   // CACHE_HIT 在 step6 前 short-circuit，补 NOT NULL 默认值防 trace 写失败
                    writeTrace(trace, List.of(), List.of(), budget, "CACHE_HIT", t0, req);
                    return vo;
                }
            }

            // step3 硬过滤（可见集 ∩ docTypes）
            FilterScope scope = step3FilterScope(vs, req.getDocTypes(), req.getKbId());
            if (!scope.allDocs && scope.docIds.isEmpty()) {
                return finishAbstain(trace, budget, t0, "NO_VISIBLE_DOCS", req, List.of(), List.of(),
                        false, List.of(), List.of());
            }

            // step4 目录路由（Phase1 降级全库，hook）
            step4DirectoryRouting(req.getQuery(), req.getKbId());

            com.superprogrammer.knowledge.retrieval.RetrievalFilterBuilder.FilterContext productionFilter =
                    new com.superprogrammer.knowledge.retrieval.RetrievalFilterBuilder().build(
                            TENANT_ID, req.getKbId(), List.of("tenant:" + TENANT_ID, "kb:" + req.getKbId()),
                            null, scope.allDocs ? List.of() : scope.docIds);
            List<com.superprogrammer.knowledge.retrieval.RetrievalCandidate> productionHits =
                    productionRetrievalGateway.retrieve(req.getQuery(), productionFilter,
                            queryPlan.strategies(), ragConfig.getMaxRerankPairs());

            // step5 dense 召回（多 qvec 并集，按 max 余弦去重排序；强制 §6.1 WHERE）
            int maxL0 = req.getMaxL0() != null ? req.getMaxL0() : ragConfig.getMaxL0Candidates();
            List<RecallHit> l0 = multiDenseRecallL0(req.getKbId(), qHalfs, scope, req.getDocTypes(), maxL0);
            List<L1DocHit> l1 = multiDenseRecallL1(req.getKbId(), qHalfs, scope, req.getDocTypes(), maxL0);
            trace.setCandidatesL1(l1HitsToJson(l1));   // Phase3 L1 trace 列（短路前未算的路径留 null）
            if (l0.isEmpty() && l1.isEmpty() && productionHits.isEmpty()) {
                return finishAbstain(trace, budget, t0, "NO_DENSE_HITS", req, List.of(), List.of(),
                        false, List.of(), List.of());
            }

            // step6 L2 候选 + rerank 代理（Phase3：L1 doc 级语义锚跨通道融合）
            boolean[] bm25Fallback = {false};
            List<L2Candidate> pool = step6L2Candidates(req.getQuery(), l0, req.getKbId(), bm25Fallback, l1);
            pool = mergeProductionCandidates(pool, productionHits);
            if (pool.size() > ragConfig.getMaxRerankPairs()) {
                throw new IllegalStateException("B3 违规: rerank pool=" + pool.size()
                        + " > maxRerankPairs=" + ragConfig.getMaxRerankPairs());
            }
            List<L2Candidate> topK = rankWithTrace(req.getKbId(), req.getQuery(), pool,
                    ragConfig.getMaxL2Read());
            List<L2Candidate> bm25OnlyCands = topK.stream().filter(L2Candidate::bm25Only).toList();
            trace.setL2LexicalFallback(bm25Fallback[0]);

            // step7 软拒答（hard/soft 双阈）：best sim 取 L0 父 sim 与 L1 doc sim 较大者（Phase3：L1 可救 L0 漏召回）
            double bestSim = topK.stream().mapToDouble(c -> Math.max(c.parentL0Sim(), c.docL1Sim())).max().orElse(0);
            boolean grayZone = bestSim < recallProps.getAbstain().getSoft();
            if (bestSim < recallProps.getAbstain().getHard()) {
                return finishAbstain(trace, budget, t0, "LOW_CONFIDENCE", req, l0, l1,
                        bm25Fallback[0], bm25OnlyCands, toEvidencePreview(topK));
            }

            // step8 evidence 装载（检索产物到此齐备；生成按需）
            List<Evidence> evidence = step8LoadEvidence(topK);
            if (evidence.isEmpty()) {
                return finishAbstain(trace, budget, t0, "NO_DENSE_HITS", req, l0, l1,
                        bm25Fallback[0], bm25OnlyCands, List.of());
            }
            com.superprogrammer.knowledge.context.EvidencePolicyService.PolicyResult evidencePolicy =
                    evidencePolicyService.apply(queryPlan.queryType(), ragConfig.getMaxL2Read(),
                            evidence.stream().map(e ->
                                    new com.superprogrammer.knowledge.context.EvidencePolicyService.EvidenceItem(
                                            e.nodeId(), e.documentId(), e.content(), e.contentHash(),
                                            e.rerankScore(), true, true)).toList(),
                            cap, bestSim, false);
            Set<Long> selectedNodeIds = evidencePolicy.evidence().stream()
                    .map(com.superprogrammer.knowledge.context.EvidencePolicyService.EvidenceItem::nodeId)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            evidence = renumberEvidence(evidence.stream()
                    .filter(e -> selectedNodeIds.contains(e.nodeId()))
                    .toList());
            if (evidence.isEmpty()) {
                return finishAbstain(trace, budget, t0, "NO_VALID_EVIDENCE", req, l0, l1,
                        bm25Fallback[0], bm25OnlyCands, List.of());
            }
            EvidencePack pack = fitToBudget(evidence, cap);
            budget.setPromptTokens(TokenEstimator.estimate(pack.prompt()));

            String verdict = grayZone ? "LOW_CONFIDENCE_SUPPORTED" : "SUPPORTED";
            trace.setCragVerdict(verdict);

            // 纯检索调试（默认）：不调 LLM 生成，直接返回候选/证据/预算。要答案去 /ask（SSE 流式）。
            if (!req.isGenerateAnswer()) {
                RagRetrieveVO retrieveOnlyVo = buildVo(traceId, false, null, "", List.of(), evidence,
                        pack.injected(), l0, l1, bm25Fallback[0], bm25OnlyCands, budget, t0);
                retrieveOnlyVo.setLowConfidence(grayZone);
                retrieveOnlyVo.setConfidenceState(evidencePolicy.confidenceState());
                writeTrace(trace, l0, pack.injected(), budget, verdict, t0, req);
                return retrieveOnlyVo;
            }

            // Grounded Answer：分批提炼 citation-bound facts，再基于事实合并最终答案。
            com.superprogrammer.knowledge.answer.GroundedAnswerService.Result grounded =
                    groundedAnswerService.synthesize(pack.injected().stream()
                                    .map(e -> new com.superprogrammer.knowledge.answer.GroundedAnswerService.Evidence(
                                            e.citationIndex(), e.content())).toList(),
                            Math.max(1, Math.min(5, pack.injected().size())),
                            batch -> extractGroundedFacts(batch, userId));
            if (grounded.facts().isEmpty()) {
                return finishAbstain(trace, budget, t0, "INSUFFICIENT", req, l0, l1,
                        bm25Fallback[0], bm25OnlyCands, toEvidencePreview(topK));
            }
            String answer = composeGroundedAnswer(req.getQuery(), grounded, userId, false);
            List<Integer> cited = citationChecker.extractAndCheck(answer, pack.injectedIndexes());
            if (cited == null) {
                answer = composeGroundedAnswer(req.getQuery(), grounded, userId, true);
                cited = citationChecker.extractAndCheck(answer, pack.injectedIndexes());
                if (cited == null) {
                    return finishAbstain(trace, budget, t0, "CITATION_CHECK_FAIL", req, l0, l1,
                            bm25Fallback[0], bm25OnlyCands, toEvidencePreview(topK));
                }
            }

            RagRetrieveVO vo = buildVo(traceId, false, null, answer, cited, evidence,
                    pack.injected(), l0, l1, bm25Fallback[0], bm25OnlyCands, budget, t0);
            vo.setLowConfidence(grayZone);
            vo.setConfidenceState(grounded.conflict() ? "CONFLICT" : evidencePolicy.confidenceState());
            writeTrace(trace, l0, pack.injected(), budget, verdict, t0, req);
            // 缓存写入（仅非灰区 SUPPORTED；灰区/abstain 不写，保不变量）
            if (!grayZone && answerCacheProps.isEnabled()) {
                List<Long> nodeIds = pack.injected().stream().map(Evidence::nodeId).toList();
                List<String> hashes = pack.injected().stream().map(Evidence::contentHash).toList();
                List<AnswerCacheService.KbScope> sigScopes = List.of(
                        new AnswerCacheService.KbScope(req.getKbId(), vs.allDocs, vs.docIds));
                String cacheSig = answerCacheService.permissionSignature(sigScopes);
                CachedPayload payload = CachedPayload.builder()
                        .answer(answer)
                        .injectedIndexes(new ArrayList<>(pack.injectedIndexes()))
                        .citations(toCitationRefs(vo.getCitations()))
                        .build();
                answerCacheService.store(req.getQuery(), qHalf, userId, List.of(req.getKbId()),
                        cacheSig, payload, nodeIds, hashes, bestSim, cacheProtocol);
            }
            return vo;
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            log.error("RAG 检索失败 traceId={}: {}", traceId, e.getMessage(), e);
            writeTrace(trace, List.of(), List.of(), budget, "ERROR", t0, req);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "检索失败: " + e.getMessage());
        }
    }

    // ============================ 多 KB 证据（阶段5 注入用）============================

    /**
     * retrieveEvidence：多 KB 证据检索（不生成答案），供 Chat/AGENT 注入 + /ask 流式 + 检索节点。
     * 同模型约束由 RagScopeResolver 保证（不同模型 cosine 不可比）。
     * 合并点 = L0 候选层：per-kb steps1/3/5/6(gather) → 合并 L0 + L2 候选 → 全局 maxBm rerank。
     * 不变式：B4（query embed 1 次，组内同模型可比）/B3（per-kb pool≤maxRerankPairs）/A2（全局 best 父L0 sim）/I3（evidence hash 复校）。
     *
     * @param effectiveKbs P4 求交后的 KB（RagScopeResolver），同 embedding_model
     * @return abstained=true → 调用方短路 ABSTAIN_MSG；否则 systemPrompt=证据上下文 + injectedIndexes
     */
    public com.superprogrammer.knowledge.dto.EvidenceResult retrieveEvidence(
            List<Long> effectiveKbs, String query, Long userId, boolean admin) {
        try (var run = ragTraceService.beginRetrieval(effectiveKbs, query, userId, "EVIDENCE")) {
            try {
                var result = retrieveEvidenceInternal(effectiveKbs, query, userId, admin);
                if (result.isAbstained()) run.abstain(result.getAbstainReason());
                else run.succeed("SUPPORTED");
                return result;
            } catch (RuntimeException e) {
                run.fail(e instanceof BusinessException be ? String.valueOf(be.getCode()) : "INTERNAL_ERROR", e.getMessage());
                throw e;
            }
        }
    }

    private com.superprogrammer.knowledge.dto.EvidenceResult retrieveEvidenceInternal(
            List<Long> effectiveKbs, String query, Long userId, boolean admin) {
        long t0 = System.currentTimeMillis();
        String traceId = UUID.randomUUID().toString().replace("-", "");
        int cap = ragConfig.computeEffectiveContextCap();
        com.superprogrammer.knowledge.dto.RagRetrieveVO.TokenBudgetVO budget =
                com.superprogrammer.knowledge.dto.RagRetrieveVO.TokenBudgetVO.builder()
                        .maxContextTokens(ragConfig.getMaxContextTokens())
                        .modelMaxContext(ragConfig.getModelMaxContext())
                        .answerTokenReserve(ragConfig.getAnswerTokenReserve())
                        .effectiveContextCap(cap).promptTokens(0).build();

        if (effectiveKbs == null || effectiveKbs.isEmpty()) {
            return com.superprogrammer.knowledge.dto.EvidenceResult.abstain(traceId, "NO_SCOPE", ABSTAIN_MSG);
        }

        RagRetrievalLog trace = newLogMerged(traceId, userId, effectiveKbs, query, DEFAULT_MODE);
        try {
            // B4：query 多路扩展（同模型，组内可比；扩展 query 级，per-KB 循环复用 qHalfs）
            KnowledgeBase kb0 = knowledgeBaseService.ensure(effectiveKbs.get(0));
            String embedModel = kb0.getEmbeddingModel();
            QueryExpansionService.ExpandedQuery eq = queryExpansionService.expand(query, embedModel, userId);
            List<String> qHalfs = eq.qHalfs();
            if (qHalfs.isEmpty()) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "query embedding 失败");
            }
            String qHalf = qHalfs.get(0);

            // step1 可见集上提（原在 per-kb 循环内）：canRead + step1VisibleSet 一次算清，循环复用 vs
            List<KbScopeCtx> validScopes = new ArrayList<>();
            for (Long kbId : effectiveKbs) {
                KnowledgeBase kb = knowledgeBaseService.ensure(kbId);
                if (!knowledgeBaseService.canRead(kb, userId, admin)) {
                    continue;
                }
                VisibleSet vs = step1VisibleSet(kb, userId, admin);
                if (!vs.allDocs && vs.docIds.isEmpty()) {
                    continue;
                }
                validScopes.add(new KbScopeCtx(kbId, vs));
            }
            AnswerCacheService.CacheProtocol cacheProtocol = answerCacheProps.isEnabled() && !validScopes.isEmpty()
                    ? answerCacheService.protocol(validScopes.stream().map(KbScopeCtx::kbId).toList(), embedModel,
                            rankingConfigService.resolve(validScopes.get(0).kbId).configVersion())
                    : null;

            // step2 答案缓存（阶段4-B）：命中则跳过 step3-7 检索，回放缓存证据上下文
            if (answerCacheProps.isEnabled() && !validScopes.isEmpty()) {
                List<AnswerCacheService.KbScope> sigScopes = validScopes.stream()
                        .map(c -> new AnswerCacheService.KbScope(c.kbId, c.vs.allDocs, c.vs.docIds))
                        .toList();
                String cacheSig = answerCacheService.permissionSignature(sigScopes);
                Optional<CachedPayload> hit = answerCacheService.lookup(qHalf, userId, cacheSig, cacheProtocol);
                if (hit.isPresent()) {
                    CachedPayload p = hit.get();
                    List<RagRetrieveVO.CitationVO> hitCitations = toCitationVOs(p.getCitations());
                    trace.setL2LexicalFallback(false);   // CACHE_HIT 在 step6 前 short-circuit，补 NOT NULL 默认值防 trace 写失败
                    writeTraceMerged(trace, List.of(), List.of(), budget, "CACHE_HIT", t0, effectiveKbs, query, userId);
                    return com.superprogrammer.knowledge.dto.EvidenceResult.builder()
                            .systemPrompt(p.getSystemPrompt())
                            .injectedIndexes(new HashSet<>(p.getInjectedIndexes() == null
                                    ? List.of() : p.getInjectedIndexes()))
                            .citations(hitCitations)
                            .abstained(false)
                            .traceId(traceId)
                            .build();
                }
            }

            int maxL0 = ragConfig.getMaxL0Candidates();
            List<RecallHit> allL0 = new ArrayList<>();
            List<L1DocHit> allL1 = new ArrayList<>();
            List<L2Candidate> allPool = new ArrayList<>();
            boolean[] bm25Fallback = {false};

            // per-kb steps3/5/6(gather)（step1 已上提，复用 vs）
            for (KbScopeCtx c : validScopes) {
                Long kbId = c.kbId;
                VisibleSet vs = c.vs;
                FilterScope scope = step3FilterScope(vs, null, kbId);
                if (!scope.allDocs && scope.docIds.isEmpty()) {
                    continue;
                }
                step4DirectoryRouting(query, kbId);
                List<RecallHit> l0 = multiDenseRecallL0(kbId, qHalfs, scope, null, maxL0);
                List<L1DocHit> l1 = multiDenseRecallL1(kbId, qHalfs, scope, null, maxL0);
                if (l0.isEmpty() && l1.isEmpty()) {
                    continue;
                }
                allL0.addAll(l0);
                allL1.addAll(l1);
                List<L2Candidate> poolK = gatherL2Candidates(query, l0, kbId, bm25Fallback, l1);
                if (poolK.size() > ragConfig.getMaxRerankPairs()) {
                    throw new IllegalStateException("B3 违规(per-kb): pool=" + poolK.size()
                            + " > maxRerankPairs=" + ragConfig.getMaxRerankPairs());
                }
                allPool.addAll(poolK);
            }

            trace.setL2LexicalFallback(bm25Fallback[0]);   // D1 trace：多 KB 合并 BM25 回退标记（NOT NULL 列）
            trace.setCandidatesL1(l1HitsToJson(allL1));    // Phase3 L1 trace 列（多 KB 合并 doc 级命中）

            if (allPool.isEmpty()) {
                writeTraceMerged(trace, allL0, List.of(), budget, "NO_DENSE_HITS", t0, effectiveKbs, query, userId);
                return com.superprogrammer.knowledge.dto.EvidenceResult.abstain(traceId, "NO_DENSE_HITS", ABSTAIN_MSG);
            }

            // 全局 rerank（maxBm 在合并集归一，跨 KB 可比）
            double globalMaxBm = allPool.stream().map(L2Candidate::bm25Rank)
                    .filter(java.util.Objects::nonNull).mapToDouble(Double::doubleValue).max().orElse(0);
            List<L2Candidate> ranked = rerankWithBoost(allPool, globalMaxBm);
            List<L2Candidate> topK = rankWithTrace(validScopes.get(0).kbId, query, ranked,
                    ragConfig.getMaxL2Read());

            // 软拒答（hard 阈；灰区 bestSim∈[hard,soft) 照注入证据，不 abstain——注入优于拒答）
            // best sim 取 L0 父 sim 与 L1 doc sim 较大者（Phase3：L1 可救 L0 漏召回）
            double bestSim = topK.stream().mapToDouble(c -> Math.max(c.parentL0Sim(), c.docL1Sim())).max().orElse(0);
            boolean grayZone = bestSim < recallProps.getAbstain().getSoft();
            if (bestSim < recallProps.getAbstain().getHard()) {
                writeTraceMerged(trace, allL0, toEvidencePreview(topK), budget, "LOW_CONFIDENCE", t0, effectiveKbs, query, userId);
                return com.superprogrammer.knowledge.dto.EvidenceResult.abstain(traceId, "LOW_CONFIDENCE", ABSTAIN_MSG);
            }

            // I3 evidence 装载 + 预算截断
            List<Evidence> evidence = step8LoadEvidence(topK);
            if (evidence.isEmpty()) {
                writeTraceMerged(trace, allL0, List.of(), budget, "NO_DENSE_HITS", t0, effectiveKbs, query, userId);
                return com.superprogrammer.knowledge.dto.EvidenceResult.abstain(traceId, "NO_DENSE_HITS", ABSTAIN_MSG);
            }
            EvidencePack pack = fitToBudget(evidence, cap);
            String ctx = "知识库证据（每个事实用 [n] 标注来源，n 为证据编号；不得编造引用）：\n"
                    + evidenceBlock(pack.injected());
            budget.setPromptTokens(TokenEstimator.estimate(systemPrompt() + ctx));

            String verdict = grayZone ? "LOW_CONFIDENCE_SUPPORTED" : "SUPPORTED";
            writeTraceMerged(trace, allL0, pack.injected(), budget, verdict, t0, effectiveKbs, query, userId);
            List<com.superprogrammer.knowledge.dto.RagRetrieveVO.CitationVO> citations = pack.injected().stream()
                    .filter(this::hasValidLocator)
                    .map(e -> com.superprogrammer.knowledge.dto.RagRetrieveVO.CitationVO.builder()
                            .index(e.citationIndex()).documentId(e.documentId())
                            .title(e.title()).nodeId(e.nodeId())
                            .docType(e.docType()).fileRef(e.fileRef())
                            .mime(e.mime()).originalName(e.originalName())
                            .page(e.locator().page()).article(e.locator().article())
                            .sheet(e.locator().sheet()).cellRange(e.locator().cellRange())
                            .bbox(e.locator().bbox()).build())
                    .toList();
            // 缓存写入（仅非灰区 SUPPORTED；灰区/abstain 不写，保不变量）
            if (!grayZone && answerCacheProps.isEnabled()) {
                List<Long> nodeIds = pack.injected().stream().map(Evidence::nodeId).toList();
                List<String> hashes = pack.injected().stream().map(Evidence::contentHash).toList();
                List<AnswerCacheService.KbScope> sigScopes = validScopes.stream()
                        .map(c -> new AnswerCacheService.KbScope(c.kbId, c.vs.allDocs, c.vs.docIds))
                        .toList();
                String cacheSig = answerCacheService.permissionSignature(sigScopes);
                CachedPayload payload = CachedPayload.builder()
                        .systemPrompt(ctx)
                        .injectedIndexes(new ArrayList<>(pack.injectedIndexes()))
                        .citations(toCitationRefs(citations))
                        .build();
                answerCacheService.store(query, qHalf, userId, effectiveKbs, cacheSig, payload,
                        nodeIds, hashes, bestSim, cacheProtocol);
            }
            return com.superprogrammer.knowledge.dto.EvidenceResult.builder()
                    .systemPrompt(ctx)
                    .injectedIndexes(pack.injectedIndexes())
                    .citations(citations)
                    .abstained(false)
                    .traceId(traceId)
                    .build();
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            log.error("retrieveEvidence 失败 traceId={}: {}", traceId, e.getMessage(), e);
            writeTraceMerged(trace, List.of(), List.of(), budget, "ERROR", t0, effectiveKbs, query, userId);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "证据检索失败: " + e.getMessage());
        }
    }

    /** 证据块文本（[n] title / content / L1 大纲+要点），供 chat SYSTEM 注入 + generate user msg 共用。 */
    private String evidenceBlock(List<Evidence> injected) {
        StringBuilder sb = new StringBuilder();
        for (Evidence e : injected) {
            sb.append("[").append(e.citationIndex()).append("] ")
                    .append(e.title() == null ? "" : e.title()).append("\n");
            sb.append(e.content() == null ? "" : e.content()).append("\n");
            String l1 = l1Line(e);
            if (!l1.isBlank()) {
                sb.append(l1).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private RagRetrievalLog newLogMerged(String traceId, Long userId, List<Long> kbIds, String query, String mode) {
        RagRetrievalLog l = new RagRetrievalLog();
        l.setTraceId(traceId);
        l.setTenantId(TENANT_ID);
        l.setUserId(userId);
        l.setIdentityType(IDENTITY_USER);
        l.setKbIds(kbIds.toString());
        l.setQuery(query);
        l.setRewrittenQuery(query);
        l.setMode(mode);
        l.setL2LexicalFallback(false);   // NOT NULL 兜底（同 newLog），防 step6 前早退路径漏登记
        return l;
    }

    private void writeTraceMerged(RagRetrievalLog trace, List<RecallHit> l0, List<Evidence> evidence,
                                  com.superprogrammer.knowledge.dto.RagRetrieveVO.TokenBudgetVO budget,
                                  String verdict, long t0, List<Long> kbIds, String query, Long userId) {
        try {
            trace.setCragVerdict(verdict);
            trace.setLatencyMs(System.currentTimeMillis() - t0);
            trace.setCandidatesL0(toJson(l0.stream().map(h -> Map.of(
                    "nodeId", h.nodeId(), "documentId", h.documentId(),
                    "title", String.valueOf(h.title()),
                    "cosineDistance", h.cosineDistance(), "cosineSimilarity", h.cosineSim()))
                    .toList()));
            trace.setEvidenceL2(toJson(evidence.stream().map(e -> Map.of(
                    "nodeId", e.nodeId(), "documentId", e.documentId(),
                    "title", String.valueOf(e.title()),
                    "contentHash", String.valueOf(e.contentHash()),
                    "citationIndex", e.citationIndex())).toList()));
            trace.setTokenBudget(toJson(budget));
            logMapper.insertTrace(trace);
        } catch (Exception e) {
            // 审计写失败不致命（不影响主流程），但升 ERROR + trace_id 便于捞漏登记根因
            log.error("写 rag_retrieval_logs 失败 traceId={}（不影响主流程结果）: {}",
                    trace.getTraceId(), e.getMessage(), e);
        }
    }

    // ============================ steps ============================

    /** step1：admin/owner→全库（短路，不缓存）；否则经可见集缓存（USER+ROLE+DEPT 三层并集）。 */
    private VisibleSet step1VisibleSet(KnowledgeBase kb, Long userId, boolean admin) {
        if (admin || knowledgeBaseService.canManage(kb, userId, admin)) {
            return new VisibleSet(true, List.of());
        }
        VisibleDocSet s = visibilitySetService.getVisibleDocs(kb.getId(), userId, false);
        return new VisibleSet(s.isAll(), s.isAll() ? List.of() : new ArrayList<>(s.docsOrEmpty()));
    }

    /** step3：可见集 ∩ docTypes。allDocs=true 表示召回 SQL 省略 document_id 谓词。 */
    private FilterScope step3FilterScope(VisibleSet vs, List<String> docTypes, Long kbId) {
        boolean hasType = docTypes != null && !docTypes.isEmpty();
        if (vs.allDocs) {
            if (!hasType) {
                return new FilterScope(true, List.of());
            }
            List<Long> typeDocs = queryMapper.listKbDocIdsByType(kbId, docTypes);
            return new FilterScope(false, typeDocs);   // admin+type：限该类型，allDocs 变 false
        }
        if (!hasType) {
            return new FilterScope(false, vs.docIds);
        }
        Set<Long> typeSet = new HashSet<>(queryMapper.listKbDocIdsByType(kbId, docTypes));
        List<Long> inter = new ArrayList<>();
        for (Long d : vs.docIds) {
            if (typeSet.contains(d)) {
                inter.add(d);
            }
        }
        return new FilterScope(false, inter);
    }

    /** step4：Phase1 无 DIRECTORY 节点 → 永远降级全库（v6 §4 允许）。留 hook。 */
    private boolean step4DirectoryRouting(String query, Long kbId) {
        return false;
    }

    /** step5：dense L0 召回（强制 §6.1 WHERE，post-ANN 可见集过滤）。 */
    private List<RecallHit> step5DenseRecall(Long kbId, String qHalf, FilterScope scope,
                                             List<String> docTypes, int maxL0) {
        List<RagQueryRow.DenseRecallRow> rows = queryMapper.denseRecallL0(
                kbId, qHalf, scope.allDocs, scope.docIds, docTypes, maxL0);
        List<RecallHit> hits = new ArrayList<>(rows.size());
        for (RagQueryRow.DenseRecallRow r : rows) {
            double dist = r.getCosineDistance() == null ? 2.0 : r.getCosineDistance();
            hits.add(new RecallHit(r.getNodeId(), r.getDocumentId(), r.getTitle(), r.getContent(), dist, 1.0 - dist));
        }
        return hits;
    }

    /**
     * step5 多 qvec：对每个 halfvec 跑 dense L0 召回，按 nodeId 去重保留 max 余弦 sim，
     * 再按 sim 降序输出（下游 topM/topD 依赖 sim 序）。
     * 同模型同通道，cosine 可比 → 用 max-sim 合并（RRF 留给 Phase2/3 跨通道）。
     */
    private List<RecallHit> multiDenseRecallL0(Long kbId, List<String> qHalfs, FilterScope scope,
                                               List<String> docTypes, int maxL0) {
        if (qHalfs.size() == 1) {
            return step5DenseRecall(kbId, qHalfs.get(0), scope, docTypes, maxL0);
        }
        Map<Long, RecallHit> best = new LinkedHashMap<>();
        for (String qh : qHalfs) {
            for (RecallHit h : step5DenseRecall(kbId, qh, scope, docTypes, maxL0)) {
                best.merge(h.nodeId(), h, (a, b) -> b.cosineSim() > a.cosineSim() ? b : a);
            }
        }
        return best.values().stream()
                .sorted((a, b) -> Double.compare(b.cosineSim(), a.cosineSim()))
                .toList();
    }

    /**
     * step5（Phase3）：多 qvec dense L1 文档召回，按 documentId 去重保留 max 余弦 sim。
     * doc 级语义锚：L0 chunk 漏召回时，L1 元数据向量仍可命中（措辞远比 chunk 稳）。
     */
    private List<L1DocHit> multiDenseRecallL1(Long kbId, List<String> qHalfs, FilterScope scope,
                                               List<String> docTypes, int maxL1) {
        Map<Long, L1DocHit> best = new LinkedHashMap<>();
        for (String qh : qHalfs) {
            for (RagQueryRow.L1RecallRow r : queryMapper.denseRecallL1(
                    kbId, qh, scope.allDocs, scope.docIds, docTypes, maxL1)) {
                double dist = r.getCosineDistance() == null ? 2.0 : r.getCosineDistance();
                L1DocHit h = new L1DocHit(r.getDocumentId(), r.getTitle(), dist, 1.0 - dist);
                best.merge(h.documentId(), h, (a, b) -> b.cosineSim() > a.cosineSim() ? b : a);
            }
        }
        return new ArrayList<>(best.values());
    }

    /**
     * step6：单 KB — gather + 按 KB 内 maxBm 加 boost 排序（行为与重构前一致）。
     * R1：L2 不重嵌。B4：无额外 embed。BM25 仅 boost（'simple' 中文弱）。
     */
    private List<L2Candidate> step6L2Candidates(String query, List<RecallHit> l0,
                                                Long kbId, boolean[] bm25Fallback, List<L1DocHit> l1Hits) {
        List<L2Candidate> gathered = gatherL2Candidates(query, l0, kbId, bm25Fallback, l1Hits);
        double maxBm = gathered.stream().map(L2Candidate::bm25Rank)
                .filter(java.util.Objects::nonNull).mapToDouble(Double::doubleValue).max().orElse(0);
        return rerankWithBoost(gathered, maxBm);
    }

    /**
     * gather：top-M L0 → RRF 融合（L0 doc 序 + L1 doc 序）top-D 文档 → L2 子节点 ∪ BM25 命中 ∪ L1 命中文档 L2。
     * Phase3：L1 命中但 L0 父未进 topM 的文档，其 L2 经 fetchL2ChildrenByDoc 补召；候选带 docL1Sim 供 rerank boost。
     * 返回候选（rerankScore=parentL0Sim 占位未加 boost；bm25Rank 原值；docL1Sim 原值），供多 KB 合并后统一 rerank。
     */
    private List<L2Candidate> gatherL2Candidates(String query, List<RecallHit> l0,
                                                 Long kbId, boolean[] bm25Fallback,
                                                 List<L1DocHit> l1Hits) {
        int topM = Math.min(ragConfig.getDenseTopM(), l0.size());
        List<RecallHit> topMHits = l0.subList(0, topM);

        // L1 doc 语义锚：documentId → max L1 sim；L1 doc 序（按 sim 降序去重，供 RRF 融合）
        Map<Long, Double> docL1Sim = new LinkedHashMap<>();
        List<Long> l1DocOrder = new ArrayList<>();
        Set<Long> l1Seen = new LinkedHashSet<>();
        if (l1Hits != null) {
            List<L1DocHit> l1Sorted = new ArrayList<>(l1Hits);
            l1Sorted.sort((a, b) -> Double.compare(b.cosineSim(), a.cosineSim()));
            for (L1DocHit h : l1Sorted) {
                docL1Sim.merge(h.documentId(), h.cosineSim(), Math::max);
                if (l1Seen.add(h.documentId())) {
                    l1DocOrder.add(h.documentId());
                }
            }
        }

        // L0 doc 序（按 L0 命中序去重）
        LinkedLongSet topDDocsSet = new LinkedLongSet();
        List<Long> l0DocOrder = new ArrayList<>();
        for (RecallHit h : l0) {
            if (topDDocsSet.set.add(h.documentId())) {
                l0DocOrder.add(h.documentId());
            }
        }

        // top-D 文档：RRF 融合 L0 doc 序 + L1 doc 序（L1 命中可把低 L0 排名的 doc 拉进 top-D）
        List<Long> topDDocIds = fuseTopDDocs(l0DocOrder, l1DocOrder, ragConfig.getDenseTopD());
        if (topDDocIds.isEmpty()) {
            return List.of();
        }

        // topM L0 命中文档集（判 L1-only doc：其 L0 父未进 topM → 需 fetchL2ChildrenByDoc 补召）
        Set<Long> topML0Docs = new HashSet<>();
        for (RecallHit h : topMHits) {
            topML0Docs.add(h.documentId());
        }

        // parentId→sim（全 L0 命中，供 BM25/L1-only 候选回查父 sim）
        Map<Long, Double> parentSim = new LinkedHashMap<>();
        List<Long> topMParentIds = new ArrayList<>();
        for (RecallHit h : topMHits) {
            parentSim.put(h.nodeId(), h.cosineSim());
            topMParentIds.add(h.nodeId());
        }
        for (RecallHit h : l0) {
            parentSim.putIfAbsent(h.nodeId(), h.cosineSim());
        }

        int perDoc = ragConfig.getPerDocL2Cap();
        Map<Long, Integer> perDocCount = new LinkedHashMap<>();
        Map<Long, L2Candidate> byNode = new LinkedHashMap<>();

        // L2 子节点（topM L0 父锚）；topMParentIds 空（l0 全空、仅 L1 命中）时跳过，交 fetchL2ChildrenByDoc
        if (!topMParentIds.isEmpty()) {
            for (RagQueryRow.L2Row r : queryMapper.fetchL2Children(kbId, topMParentIds, topDDocIds)) {
                if (perDocCount.merge(r.getDocumentId(), 1, Integer::sum) > perDoc) {
                    continue;
                }
                byNode.put(r.getNodeId(), toCandidate(r, simOf(r.getParentId(), parentSim), null, false,
                        simForDoc(docL1Sim, r.getDocumentId())));
            }
        }

        // Phase3：L1 命中但 L0 父未进 topM 的文档，其 L2 经 doc 维度补召（不限 parent∈topM）
        List<Long> l1OnlyDocs = new ArrayList<>();
        for (Long docId : topDDocIds) {
            if (!topML0Docs.contains(docId)) {
                l1OnlyDocs.add(docId);
            }
        }
        if (!l1OnlyDocs.isEmpty()) {
            for (RagQueryRow.L2Row r : queryMapper.fetchL2ChildrenByDoc(kbId, l1OnlyDocs)) {
                if (perDocCount.merge(r.getDocumentId(), 1, Integer::sum) > perDoc) {
                    continue;
                }
                byNode.put(r.getNodeId(), toCandidate(r, simOf(r.getParentId(), parentSim), null, false,
                        simForDoc(docL1Sim, r.getDocumentId())));
            }
        }

        // BM25 命中并集（Phase2：jieba 分词后查 content_tokens_tsv，治"换说法召回不到"的词法兜底）
        List<RagQueryRow.L2Row> bm25 = queryMapper.bm25HitsJieba(kbId,
                com.superprogrammer.knowledge.util.JiebaTokenizer.tokenize(query), topDDocIds);
        for (RagQueryRow.L2Row r : bm25) {
            double psim = simOf(r.getParentId(), parentSim);
            boolean bmOnly = !byNode.containsKey(r.getNodeId());
            if (bmOnly) {
                bm25Fallback[0] = true;   // 纯 BM25（无父锚）候选进入 pool
            }
            byNode.merge(r.getNodeId(), toCandidate(r, psim, r.getBm25Rank(), bmOnly, simForDoc(docL1Sim, r.getDocumentId())),
                    (a, b) -> b.bm25Rank() != null ? b : a);
        }
        return new ArrayList<>(byNode.values());
    }

    /**
     * 加 BM25 + L1 boost + 排序。maxBm/maxL1 由候选集归一（单 KB=KB 内最大；多 KB=合并集全局最大，可比）。
     * Phase3：L1 命中文档的候选额外 L1 boost（doc 级语义锚命中，措辞远比 chunk 稳，治 L0 漏召回）。
     * bm25/L1 boost 同用 bm25BoostMax 上限尺度；parentL0Sim 仍主导（boost 仅作抬升，不主导排序）。
     */
    private List<L2Candidate> rerankWithBoost(List<L2Candidate> candidates, double maxBm) {
        double maxL1 = candidates.stream().mapToDouble(L2Candidate::docL1Sim).max().orElse(0);
        if (maxBm <= 0) {
            maxBm = 1;
        }
        if (maxL1 <= 0) {
            maxL1 = 1;
        }
        double boostMax = ragConfig.getBm25BoostMax();
        List<L2Candidate> pool = new ArrayList<>(candidates.size());
        for (L2Candidate c : candidates) {
            double bmBoost = c.bm25Rank() == null ? 0 : boostMax * (c.bm25Rank() / maxBm);
            double l1Boost = c.docL1Sim() <= 0 ? 0 : boostMax * (c.docL1Sim() / maxL1);
            pool.add(new L2Candidate(c.nodeId(), c.documentId(), c.parentId(), c.title(),
                    c.content(), c.contentHash(), c.parentL0Sim(), c.bm25Rank(),
                    c.parentL0Sim() + bmBoost + l1Boost, c.bm25Only(), c.docL1Sim()));
        }
        pool.sort((a, b) -> Double.compare(b.rerankScore(), a.rerankScore()));
        return pool;
    }

    /**
     * P0 只建立可观测契约，当前实际排序仍是启发式代理，不能伪记为已经调用 LLM/Rerank。
     */
    private List<L2Candidate> rankWithTrace(Long kbId, String query, List<L2Candidate> rankedPool, int limit) {
        RankingConfigService.ResolvedRankingConfig config = rankingConfigService.resolve(kbId);
        String candidateSummary = rankedPool.stream()
                .map(c -> String.valueOf(c.nodeId()))
                .collect(java.util.stream.Collectors.joining(","));
        try (var ranking = ragTraceService.beginRanking(config.mode(), config.mode(),
                config.configId(), config.configVersion(), rankedPool.size(), candidateSummary, null)) {
            try {
                List<com.superprogrammer.knowledge.retrieval.RetrievalCandidate> candidates = rankedPool.stream()
                        .limit(config.candidateLimit())
                        .map(c -> new com.superprogrammer.knowledge.retrieval.RetrievalCandidate(
                                String.valueOf(c.nodeId()), c.nodeId(), c.documentId(), "FUSED", c.rerankScore(),
                                c.title(), c.content()))
                        .toList();
                List<com.superprogrammer.knowledge.ranking.RankingResult> results =
                        rankingEngine.rank(config.mode(), query, candidates, config.model());
                Map<Long, L2Candidate> remaining = rankedPool.stream().collect(java.util.stream.Collectors.toMap(
                        L2Candidate::nodeId, c -> c, (a, b) -> a, LinkedHashMap::new));
                List<L2Candidate> ordered = new ArrayList<>();
                for (com.superprogrammer.knowledge.ranking.RankingResult result : results) {
                    Long nodeId;
                    try { nodeId = Long.valueOf(result.candidateId()); }
                    catch (NumberFormatException e) { throw new IllegalArgumentException("ranking candidate id invalid"); }
                    L2Candidate original = remaining.remove(nodeId);
                    if (original == null) throw new IllegalArgumentException("ranking candidate id invalid: " + nodeId);
                    ordered.add(new L2Candidate(original.nodeId(), original.documentId(), original.parentId(),
                            original.title(), original.content(), original.contentHash(), original.parentL0Sim(),
                            original.bm25Rank(), result.score(), original.bm25Only(), original.docL1Sim()));
                }
                if ("DISABLED".equals(config.mode())) ordered.addAll(remaining.values());
                List<L2Candidate> topK = pickTopK(ordered, Math.min(limit, config.finalLimit()));
                ranking.succeed(topK.size());
                return topK;
            } catch (RuntimeException e) {
                if ("FALLBACK_RRF".equalsIgnoreCase(config.fallbackPolicy())) {
                    List<L2Candidate> fallback = pickTopK(rankedPool, Math.min(limit, config.finalLimit()));
                    ranking.succeed(fallback.size());
                    return fallback;
                }
                ranking.fail(e.getMessage());
                throw e;
            }
        }
    }

    private List<L2Candidate> pickTopK(List<L2Candidate> pool, int k) {
        return pool.subList(0, Math.min(k, pool.size()));
    }

    private List<L2Candidate> mergeProductionCandidates(
            List<L2Candidate> pgCandidates,
            List<com.superprogrammer.knowledge.retrieval.RetrievalCandidate> productionHits) {
        Map<Long, L2Candidate> merged = new LinkedHashMap<>();
        pgCandidates.forEach(candidate -> merged.put(candidate.nodeId(), candidate));
        for (com.superprogrammer.knowledge.retrieval.RetrievalCandidate hit : productionHits) {
            if (hit.nodeId() == null || hit.documentId() == null || hit.contentHash() == null) continue;
            merged.putIfAbsent(hit.nodeId(), new L2Candidate(hit.nodeId(), hit.documentId(), null,
                    hit.title(), hit.content(), hit.contentHash(), hit.rawScore(), null,
                    hit.rawScore(), false, 0));
        }
        return new ArrayList<>(merged.values());
    }

    /** step8：I3 复校（content_hash 现值）+ L1 装载 + 编号。失配丢弃并记 REINDEX。 */
    private List<Evidence> step8LoadEvidence(List<L2Candidate> topK) {
        List<Evidence> out = new ArrayList<>();
        int idx = 1;
        for (L2Candidate c : topK) {
            RagQueryRow.HashVerifyRow hv = queryMapper.reverifyNode(c.nodeId());
            if (hv == null || hv.getNodeHash() == null
                    || !hv.getNodeHash().equals(c.contentHash())) {
                log.warn("I3 evidence content_hash 失配 nodeId={} → 丢弃，建议补 REINDEX", c.nodeId());
                continue;   // I3：丢弃，Phase1 仅记日志（REINDEX 由阶段7 对账兜底）
            }
            L1Outline l1 = loadL1(c.documentId());
            out.add(new Evidence(c.nodeId(), c.documentId(), c.title(), c.content(),
                    c.contentHash(), l1.docType, l1.fileRef, l1.mime, l1.originalName,
                    l1.outline, l1.rules, idx, c.rerankScore(), parseLocator(hv.getMetadata())));
            idx++;
        }
        return out;
    }

    private List<Evidence> renumberEvidence(List<Evidence> evidence) {
        List<Evidence> result = new ArrayList<>(evidence.size());
        int citationIndex = 1;
        for (Evidence e : evidence) {
            result.add(new Evidence(e.nodeId(), e.documentId(), e.title(), e.content(),
                    e.contentHash(), e.docType(), e.fileRef(), e.mime(), e.originalName(),
                    e.l1Outline(), e.l1Rules(), citationIndex++, e.rerankScore(), e.locator()));
        }
        return result;
    }

    // ============================ 生成 + Citation ============================

    private EvidencePack fitToBudget(List<Evidence> evidence, int cap) {
        String system = systemPrompt();
        StringBuilder user = new StringBuilder();
        user.append("问题：").append("\n\n证据：\n");
        int used = TokenEstimator.estimate(system) + TokenEstimator.estimate(user.toString());
        Set<Integer> injected = new HashSet<>();
        List<Evidence> injectedList = new ArrayList<>();
        int l1Cap = ragConfig.getL1PerDocTokenCap();
        for (Evidence e : evidence) {
            int before = used;
            StringBuilder block = new StringBuilder();
            block.append("[").append(e.citationIndex()).append("] ").append(e.title() == null ? "" : e.title()).append("\n");
            block.append(e.content() == null ? "" : e.content()).append("\n");
            String l1Line = l1Line(e);
            if (!l1Line.isBlank()) {
                block.append(l1Line).append("\n");
            }
            String blockStr = block.toString();
            int blockTok = TokenEstimator.estimate(blockStr);
            if (used + blockTok <= cap) {
                user.append(blockStr);
                used += blockTok;
            } else {
                int remainTok = cap - used;
                if (remainTok <= 0) {
                    break;
                }
                int keepChars = remainTok * 4;
                String truncated = keepChars >= blockStr.length() ? blockStr : blockStr.substring(0, keepChars);
                user.append(truncated).append("\n");
                used = cap;
            }
            injected.add(e.citationIndex());
            injectedList.add(e);
            if (used >= cap) {
                break;
            }
        }
        // l1Cap 仅作 per-doc 软约束（已在 l1Line 截断），整体受 cap 约束
        return new EvidencePack(system + user, injected, injectedList);
    }

    private String l1Line(Evidence e) {
        StringBuilder sb = new StringBuilder();
        if (e.l1Outline() != null && !e.l1Outline().isBlank()) {
            sb.append("(大纲：").append(clamp(e.l1Outline(), 200)).append(")");
        }
        if (e.l1Rules() != null && !e.l1Rules().isBlank()) {
            if (!sb.isEmpty()) {
                sb.append(" ");
            }
            sb.append("(要点：").append(clamp(e.l1Rules(), 200)).append(")");
        }
        return sb.toString();
    }

    private String generate(String query, EvidencePack pack, Long userId, boolean strict) {
        String system = strict ? systemPromptStrict(pack.injectedIndexes()) : systemPrompt();
        LlmRequest req = LlmRequest.builder()
                .messages(List.of(
                        LlmMessage.builder().role("system").content(system).build(),
                        LlmMessage.builder().role("user").content(userMessage(query, pack.injected())).build()))
                .temperature(ragConfig.getChatTemperature())
                .maxTokens(ragConfig.getChatMaxTokens())
                .stream(false)
                .build();
        return llmGateway.chat(req, userId).getContent();
    }

    private String userMessage(String query, List<Evidence> injected) {
        StringBuilder sb = new StringBuilder();
        sb.append("问题：").append(query).append("\n\n证据：\n");
        for (Evidence e : injected) {
            sb.append("[").append(e.citationIndex()).append("] ")
                    .append(e.title() == null ? "" : e.title()).append("\n");
            sb.append(e.content() == null ? "" : e.content()).append("\n");
            String l1 = l1Line(e);
            if (!l1.isBlank()) {
                sb.append(l1).append("\n");
            }
            sb.append("\n");
        }
        sb.append("\n回答：");
        return sb.toString();
    }

    private String systemPrompt() {
        return "你是企业知识库检索助手。仅根据下方证据回答，每个事实用 [n] 标注来源（n 为证据编号）。"
                + "不要编造引用，不要使用证据之外的知识。若证据不足以回答，直接回复“未找到可访问的相关知识。”";
    }

    private String systemPromptStrict(Set<Integer> injected) {
        return systemPrompt() + " 只能引用编号 " + injected + " 中的证据，禁止出现其他数字编号的引用。";
    }

    // ============================ VO + trace ============================

    private RagRetrieveVO finishAbstain(RagRetrievalLog trace, RagRetrieveVO.TokenBudgetVO budget,
                                        long t0, String verdict, RagRetrieveRequest req,
                                        List<RecallHit> l0, List<L1DocHit> l1,
                                        boolean bm25Fallback, List<L2Candidate> bm25OnlyCands,
                                        List<Evidence> evidencePreview) {
        trace.setCragVerdict(verdict);
        RagRetrieveVO vo = RagRetrieveVO.builder()
                .traceId(trace.getTraceId())
                .abstained(true)
                .abstainReason(verdict)
                .answer(ABSTAIN_MSG)
                .citations(List.of())
                .candidatesL0(toRecallVOs(l0))
                .candidatesL1(toL1RecallVOs(l1))
                .bm25Fallback(bm25Fallback)
                .candidatesBm25(toBm25VOs(bm25OnlyCands))
                .evidenceL2(toEvidenceVOs(evidencePreview))
                .tokenBudget(budget)
                .latencyMs(System.currentTimeMillis() - t0)
                .build();
        writeTrace(trace, l0, evidencePreview, budget, verdict, t0, req);
        return vo;
    }

    private RagRetrieveVO buildVo(String traceId, boolean abstained, String reason, String answer,
                                 List<Integer> cited, List<Evidence> allEvidence,
                                 List<Evidence> injected, List<RecallHit> l0,
                                 List<L1DocHit> l1, boolean bm25Fallback, List<L2Candidate> bm25OnlyCands,
                                 RagRetrieveVO.TokenBudgetVO budget, long t0) {
        Map<Integer, Evidence> byIdx = new LinkedHashMap<>();
        for (Evidence e : injected) {
            byIdx.put(e.citationIndex(), e);
        }
        List<RagRetrieveVO.CitationVO> citations = new ArrayList<>();
        for (Integer i : cited) {
            Evidence e = byIdx.get(i);
            if (e != null && hasValidLocator(e)) {
                citations.add(RagRetrieveVO.CitationVO.builder()
                        .index(i).documentId(e.documentId()).title(e.title()).nodeId(e.nodeId())
                        .docType(e.docType()).fileRef(e.fileRef()).mime(e.mime()).originalName(e.originalName())
                        .page(e.locator().page()).article(e.locator().article())
                        .sheet(e.locator().sheet()).cellRange(e.locator().cellRange())
                        .bbox(e.locator().bbox())
                        .build());
            }
        }
        return RagRetrieveVO.builder()
                .traceId(traceId).abstained(abstained).abstainReason(reason).answer(answer)
                .citations(citations)
                .candidatesL0(toRecallVOs(l0))
                .candidatesL1(toL1RecallVOs(l1))
                .bm25Fallback(bm25Fallback)
                .candidatesBm25(toBm25VOs(bm25OnlyCands))
                .evidenceL2(toEvidenceVOs(allEvidence))
                .tokenBudget(budget)
                .latencyMs(System.currentTimeMillis() - t0)
                .build();
    }

    private List<com.superprogrammer.knowledge.answer.GroundedAnswerService.Fact> extractGroundedFacts(
            List<com.superprogrammer.knowledge.answer.GroundedAnswerService.Evidence> evidence, Long userId) {
        String evidenceJson;
        try {
            evidenceJson = objectMapper.writeValueAsString(evidence);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "证据批次序列化失败");
        }
        LlmRequest request = LlmRequest.builder()
                .messages(List.of(
                        LlmMessage.builder().role("system").content(
                                "仅从证据提炼事实。返回 JSON 数组，每项字段 subject、value、citationIds；禁止使用证据外知识。")
                                .build(),
                        LlmMessage.builder().role("user").content(evidenceJson).build()))
                .temperature(0.0).maxTokens(ragConfig.getChatMaxTokens()).stream(false)
                .callPurpose("GROUNDING_FACT_EXTRACTION").build();
        String content = llmGateway.chat(request, userId).getContent();
        try {
            return objectMapper.readValue(content, objectMapper.getTypeFactory().constructCollectionType(
                    List.class, com.superprogrammer.knowledge.answer.GroundedAnswerService.Fact.class));
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.UNPROCESSABLE, "事实提炼模型未返回合法 JSON");
        }
    }

    private String composeGroundedAnswer(String query,
                                         com.superprogrammer.knowledge.answer.GroundedAnswerService.Result grounded,
                                         Long userId, boolean strict) {
        String rule = strict
                ? "必须仅使用下列事实，每句话至少带一个已有 [n] 引用，不得新增编号。"
                : "仅使用下列事实回答，并保留对应 [n] 引用；冲突时明确列出不同说法。";
        LlmRequest request = LlmRequest.builder()
                .messages(List.of(
                        LlmMessage.builder().role("system").content(rule).build(),
                        LlmMessage.builder().role("user").content("问题：" + query + "\n事实：\n"
                                + groundedAnswerService.renderFacts(grounded.facts())).build()))
                .temperature(ragConfig.getChatTemperature()).maxTokens(ragConfig.getChatMaxTokens())
                .stream(false).callPurpose("GROUNDED_ANSWER_COMPOSITION").build();
        return llmGateway.chat(request, userId).getContent();
    }

    private boolean hasValidLocator(Evidence evidence) {
        LocatorData locator = evidence.locator();
        return locator != null && citationVerifier.verify(
                new com.superprogrammer.knowledge.citation.CitationVerifier.Citation(
                        evidence.nodeId(), locator.canonical(), evidence.contentHash(), true, true),
                evidence.content(), ignored -> true);
    }

    @SuppressWarnings("unchecked")
    private LocatorData parseLocator(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) return null;
        try {
            Map<String, Object> metadata = objectMapper.readValue(metadataJson, Map.class);
            Object locatorRaw = metadata.get("locator");
            if (!(locatorRaw instanceof Map<?, ?> locator) || locator.isEmpty()) return null;
            String page = range(locator.get("pageStart"), locator.get("pageEnd"));
            String sheet = stringValue(locator.get("sheetName"));
            String cellRange = range(locator.get("cellStart"), locator.get("cellEnd"));
            String article = null;
            Object titlePath = metadata.get("titlePath");
            if (titlePath instanceof List<?> path && !path.isEmpty()) {
                article = path.stream().map(String::valueOf)
                        .collect(java.util.stream.Collectors.joining(" / "));
            }
            String bbox = locator.get("boundingBoxes") == null ? null
                    : objectMapper.writeValueAsString(locator.get("boundingBoxes"));
            String canonical = objectMapper.writeValueAsString(locator);
            return new LocatorData(canonical, page, article, sheet, cellRange, bbox);
        } catch (Exception e) {
            log.warn("RAG citation locator metadata invalid node metadata ignored");
            return null;
        }
    }

    private String range(Object start, Object end) {
        String from = stringValue(start);
        String to = stringValue(end);
        if (from == null) return to;
        if (to == null || from.equals(to)) return from;
        return from + "-" + to;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private void writeTrace(RagRetrievalLog trace, List<RecallHit> l0, List<Evidence> evidence,
                            RagRetrieveVO.TokenBudgetVO budget, String verdict, long t0,
                            RagRetrieveRequest req) {
        try {
            trace.setCragVerdict(verdict);
            trace.setLatencyMs(System.currentTimeMillis() - t0);
            trace.setCandidatesL0(toJson(l0.stream().map(h -> Map.of(
                    "nodeId", h.nodeId(), "documentId", h.documentId(),
                    "title", String.valueOf(h.title()),
                    "cosineDistance", h.cosineDistance(), "cosineSimilarity", h.cosineSim()))
                    .toList()));
            trace.setEvidenceL2(toJson(evidence.stream().map(e -> Map.of(
                    "nodeId", e.nodeId(), "documentId", e.documentId(),
                    "title", String.valueOf(e.title()),
                    "contentHash", String.valueOf(e.contentHash()),
                    "citationIndex", e.citationIndex())).toList()));
            trace.setTokenBudget(toJson(budget));
            logMapper.insertTrace(trace);
        } catch (Exception e) {
            // 审计写失败不致命（不影响主流程），但升 ERROR + trace_id 便于捞漏登记根因
            log.error("写 rag_retrieval_logs 失败 traceId={}（不影响主流程结果）: {}",
                    trace.getTraceId(), e.getMessage(), e);
        }
    }

    /** L1 doc 级命中 → trace JSON（documentId/title/cosine 距离与相似度）。短路前未算 → null（不写）。 */
    private String l1HitsToJson(List<L1DocHit> l1) {
        if (l1 == null || l1.isEmpty()) {
            return null;
        }
        return toJson(l1.stream().map(h -> Map.of(
                "documentId", h.documentId(),
                "title", String.valueOf(h.title()),
                "cosineDistance", h.cosineDistance(),
                "cosineSimilarity", h.cosineSim()))
                .toList());
    }

    private RagRetrievalLog newLog(String traceId, Long userId, RagRetrieveRequest req, String mode) {
        RagRetrievalLog l = new RagRetrievalLog();
        l.setTraceId(traceId);
        l.setTenantId(TENANT_ID);
        l.setUserId(userId);
        l.setIdentityType(IDENTITY_USER);
        l.setKbIds(String.valueOf(req.getKbId()));
        l.setQuery(req.getQuery());
        l.setRewrittenQuery(req.getQuery());
        l.setMode(mode);
        // NOT NULL 兜底：l2_lexical_fallback 装箱 Boolean 默认 null，step6 前早退路径（NO_VISIBLE_DOCS/
        // 早期 NO_DENSE_HITS）不会设它 → insertTrace 撞 NOT NULL → writeTrace catch 静默丢。这里统一 false。
        l.setL2LexicalFallback(false);
        return l;
    }

    // ============================ 小工具 ============================

    private List<Evidence> toEvidencePreview(List<L2Candidate> topK) {
        List<Evidence> out = new ArrayList<>();
        int idx = 1;
        for (L2Candidate c : topK) {
            out.add(new Evidence(c.nodeId(), c.documentId(), c.title(), c.content(),
                    c.contentHash(), null, null, null, null, null, null, idx, c.rerankScore(), null));
            idx++;
        }
        return out;
    }

    private List<RagRetrieveVO.RecallHitVO> toRecallVOs(List<RecallHit> l0) {
        return l0.stream().map(h -> RagRetrieveVO.RecallHitVO.builder()
                .nodeId(h.nodeId()).documentId(h.documentId()).title(h.title()).content(h.content())
                .cosineDistance(h.cosineDistance()).cosineSimilarity(h.cosineSim()).build()).toList();
    }

    private List<RagRetrieveVO.L1RecallHitVO> toL1RecallVOs(List<L1DocHit> l1) {
        if (l1 == null || l1.isEmpty()) {
            return List.of();
        }
        // 仅 /retrieve 调试链路调用：每命中 doc 取 l1_metadata（summary/outline/rules）展示。
        // hot path（retrieveEvidence）不经此方法，无 per-doc 查询开销。
        return l1.stream().map(h -> {
            L1Display d = loadL1Display(h.documentId());
            return RagRetrieveVO.L1RecallHitVO.builder()
                    .documentId(h.documentId()).title(h.title())
                    .cosineDistance(h.cosineDistance()).cosineSimilarity(h.cosineSim())
                    .summary(d.summary()).outline(d.outline()).importantRules(d.rules()).build();
        }).toList();
    }

    /** L1 doc 显示元数据（summary/outline/rules 拼接），调试展示用。无 l1_metadata → 三 null。 */
    private L1Display loadL1Display(Long docId) {
        RagQueryRow.L1Row row = queryMapper.fetchL1Metadata(docId);
        if (row == null || row.getL1Metadata() == null || row.getL1Metadata().isBlank()) {
            return new L1Display(null, null, null);
        }
        try {
            L1Metadata l1 = objectMapper.readValue(row.getL1Metadata(), L1Metadata.class);
            String outline = (l1.getOutline() == null || l1.getOutline().isEmpty())
                    ? null : String.join("；", l1.getOutline());
            String rules = (l1.getImportantRules() == null || l1.getImportantRules().isEmpty())
                    ? null : String.join("；", l1.getImportantRules());
            return new L1Display(l1.getSummary(), outline, rules);
        } catch (Exception e) {
            log.warn("L1 显示元数据解析失败 docId={}: {}", docId, e.getMessage());
            return new L1Display(null, null, null);
        }
    }

    /** topK 中纯 BM25 候选（bm25Only=true）→ Bm25HitVO（调试展示词法兜底通道贡献）。 */
    private List<RagRetrieveVO.Bm25HitVO> toBm25VOs(List<L2Candidate> bm25OnlyCands) {
        if (bm25OnlyCands == null || bm25OnlyCands.isEmpty()) {
            return List.of();
        }
        return bm25OnlyCands.stream().map(c -> RagRetrieveVO.Bm25HitVO.builder()
                .nodeId(c.nodeId()).documentId(c.documentId()).title(c.title())
                .bm25Rank(c.bm25Rank()).build()).toList();
    }

    private List<RagRetrieveVO.EvidenceVO> toEvidenceVOs(List<Evidence> ev) {
        return ev.stream().map(e -> RagRetrieveVO.EvidenceVO.builder()
                .nodeId(e.nodeId()).documentId(e.documentId()).title(e.title())
                .content(e.content()).contentHash(e.contentHash()).docType(e.docType())
                .fileRef(e.fileRef()).mime(e.mime()).originalName(e.originalName())
                .citationIndex(e.citationIndex()).rerankScore(e.rerankScore()).build()).toList();
    }

    /** Phase3：RRF 融合 L0 doc 序 + L1 doc 序 → top-D doc（两通道分数尺度不可比，按排名归一）。 */
    private List<Long> fuseTopDDocs(List<Long> l0DocOrder, List<Long> l1DocOrder, int topD) {
        if (l1DocOrder.isEmpty()) {
            return l0DocOrder.stream().limit(topD).toList();
        }
        if (l0DocOrder.isEmpty()) {
            return l1DocOrder.stream().limit(topD).toList();
        }
        int k = recallProps.getRrf().getK();
        List<RrfFusion.WeightedList<Long>> lists = List.of(
                new RrfFusion.WeightedList<>(l0DocOrder, recallProps.getRrf().getWeightL0Vector()),
                new RrfFusion.WeightedList<>(l1DocOrder, recallProps.getRrf().getWeightL1Vector()));
        return RrfFusion.sortByScoreDesc(RrfFusion.fuseWeighted(lists, k)).stream().limit(topD).toList();
    }

    /** doc 的 L1 sim（无命中→0），供 L2 候选 docL1Sim 字段。 */
    private double simForDoc(Map<Long, Double> docL1Sim, Long docId) {
        if (docId == null) {
            return 0;
        }
        Double s = docL1Sim.get(docId);
        return s == null ? 0 : s;
    }

    private double simOf(Long parentId, Map<Long, Double> parentSim) {
        if (parentId == null) {
            return 0;
        }
        Double s = parentSim.get(parentId);
        return s == null ? 0 : s;
    }

    private L2Candidate toCandidate(RagQueryRow.L2Row r, double psim, Double bm25, boolean bmOnly, double docL1Sim) {
        return new L2Candidate(r.getNodeId(), r.getDocumentId(), r.getParentId(), r.getTitle(),
                r.getContent(), r.getContentHash(), psim, bm25, psim, bmOnly, docL1Sim);
    }

    private record L1Outline(String docType, String outline, String rules,
                             String fileRef, String mime, String originalName) {
    }

    /** L1 显示元数据（summary + outline/rules 拼接串），调试展示用。 */
    private record L1Display(String summary, String outline, String rules) {
    }

    private L1Outline loadL1(Long docId) {
        RagQueryRow.L1Row row = queryMapper.fetchL1Metadata(docId);
        if (row == null || row.getL1Metadata() == null || row.getL1Metadata().isBlank()) {
            return new L1Outline(row == null ? null : row.getDocType(), null, null,
                    row == null ? null : row.getFileRef(), row == null ? null : row.getMime(),
                    row == null ? null : row.getOriginalName());
        }
        try {
            L1Metadata l1 = objectMapper.readValue(row.getL1Metadata(), L1Metadata.class);
            String outline = l1.getOutline() == null ? null : String.join("；", l1.getOutline());
            String rules = l1.getImportantRules() == null ? null : String.join("；", l1.getImportantRules());
            return new L1Outline(row.getDocType(), outline, rules,
                    row.getFileRef(), row.getMime(), row.getOriginalName());
        } catch (Exception e) {
            log.warn("L1 元数据解析失败 docId={}: {}", docId, e.getMessage());
            return new L1Outline(row.getDocType(), null, null,
                    row.getFileRef(), row.getMime(), row.getOriginalName());
        }
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return null;
        }
    }

    private String clamp(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    /** 保持插入顺序的 long 去重集合。 */
    private static class LinkedLongSet {
        final Set<Long> set = new LinkedHashSet<>();
        void add(Long l) { set.add(l); }
        int size() { return set.size(); }
        List<Long> list() { return new ArrayList<>(set); }
    }

    private record FilterScope(boolean allDocs, List<Long> docIds) {
    }

    /** retrieveEvidence 缓存用：per-kb 可见集上下文（step1 上提产物，循环复用 vs）。 */
    private record KbScopeCtx(Long kbId, VisibleSet vs) {
    }

    /** CachedPayload.CitationRef → RagRetrieveVO.CitationVO（命中回放）。 */
    private List<RagRetrieveVO.CitationVO> toCitationVOs(List<CachedPayload.CitationRef> refs) {
        if (refs == null || refs.isEmpty()) {
            return List.of();
        }
        return refs.stream().map(r -> RagRetrieveVO.CitationVO.builder()
                .index(r.getIndex()).documentId(r.getDocumentId())
                .title(r.getTitle()).nodeId(r.getNodeId())
                .docType(r.getDocType()).fileRef(r.getFileRef()).mime(r.getMime()).originalName(r.getOriginalName())
                .build()).toList();
    }

    /** RagRetrieveVO.CitationVO → CachedPayload.CitationRef（写入缓存）。 */
    private List<CachedPayload.CitationRef> toCitationRefs(List<RagRetrieveVO.CitationVO> citations) {
        if (citations == null || citations.isEmpty()) {
            return List.of();
        }
        return citations.stream().map(c -> CachedPayload.CitationRef.builder()
                .index(c.getIndex()).documentId(c.getDocumentId())
                .title(c.getTitle()).nodeId(c.getNodeId())
                .docType(c.getDocType()).fileRef(c.getFileRef()).mime(c.getMime()).originalName(c.getOriginalName())
                .build()).toList();
    }
}
