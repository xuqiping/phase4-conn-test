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
import com.superprogrammer.knowledge.dto.CachedPayload;
import com.superprogrammer.knowledge.service.internal.AnswerCacheService;
import com.superprogrammer.knowledge.service.internal.CitationChecker;
import com.superprogrammer.knowledge.service.internal.L1Metadata;
import com.superprogrammer.knowledge.service.internal.VisibleDocSet;
import com.superprogrammer.knowledge.util.HalfVecUtil;
import com.superprogrammer.knowledge.util.TokenEstimator;
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
    private static final String EMBED_MODEL_FALLBACK = "doubao-embedding-vision";
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

    // ============================ 内部 record ============================

    private record VisibleSet(boolean allDocs, List<Long> docIds) {
    }

    private record RecallHit(Long nodeId, Long documentId, String title,
                             double cosineDistance, double cosineSim) {
    }

    private record L2Candidate(Long nodeId, Long documentId, Long parentId, String title,
                               String content, String contentHash,
                               double parentL0Sim, Double bm25Rank, double rerankScore,
                               boolean bm25Only) {
    }

    private record Evidence(Long nodeId, Long documentId, String title, String content,
                            String contentHash, String docType,
                            String l1Outline, String l1Rules,
                            int citationIndex, double rerankScore) {
    }

    private record EvidencePack(String prompt, Set<Integer> injectedIndexes,
                                List<Evidence> injected) {
    }

    // ============================ 入口 ============================

    public RagRetrieveVO retrieve(RagRetrieveRequest req, Long userId) {
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
            boolean admin = req.isAdminHint();
            if (!knowledgeBaseService.canRead(kb, userId, admin)) {
                throw new BusinessException(ErrorCode.FORBIDDEN, "无权访问该知识库");
            }
            String embedModel = (kb.getEmbeddingModel() == null || kb.getEmbeddingModel().isBlank())
                    ? EMBED_MODEL_FALLBACK : kb.getEmbeddingModel();

            // step1 可见集
            VisibleSet vs = step1VisibleSet(kb, userId, admin);
            if (!vs.allDocs && vs.docIds.isEmpty()) {
                return finishAbstain(trace, budget, t0, "NO_VISIBLE_DOCS", req, List.of(), List.of());
            }

            // B4：query embed 仅一次（dense 召回复用）
            float[] qVec = llmGateway.embed(req.getQuery(), embedModel);
            if (qVec.length != HalfVecUtil.DIM) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                        "query embedding 维度不匹配 expected=" + HalfVecUtil.DIM + " actual=" + qVec.length);
            }
            String qHalf = HalfVecUtil.toHalfVec(qVec);

            // step2 答案缓存（阶段4-B）：命中则跳过 step3-8 + 生成，回放缓存 answer
            if (answerCacheProps.isEnabled()) {
                List<AnswerCacheService.KbScope> sigScopes = List.of(
                        new AnswerCacheService.KbScope(req.getKbId(), vs.allDocs, vs.docIds));
                String cacheSig = answerCacheService.permissionSignature(sigScopes);
                Optional<CachedPayload> hit = answerCacheService.lookup(qHalf, userId, cacheSig);
                if (hit.isPresent()) {
                    CachedPayload p = hit.get();
                    budget.setPromptTokens(0);
                    RagRetrieveVO vo = RagRetrieveVO.builder()
                            .traceId(traceId).abstained(false).abstainReason(null)
                            .answer(p.getAnswer())
                            .citations(toCitationVOs(p.getCitations()))
                            .candidatesL0(List.of()).evidenceL2(List.of())
                            .tokenBudget(budget).latencyMs(System.currentTimeMillis() - t0).build();
                    trace.setL2LexicalFallback(false);   // CACHE_HIT 在 step6 前 short-circuit，补 NOT NULL 默认值防 trace 写失败
                    writeTrace(trace, List.of(), List.of(), budget, "CACHE_HIT", t0, req);
                    return vo;
                }
            }

            // step3 硬过滤（可见集 ∩ docTypes）
            FilterScope scope = step3FilterScope(vs, req.getDocTypes(), req.getKbId());
            if (!scope.allDocs && scope.docIds.isEmpty()) {
                return finishAbstain(trace, budget, t0, "NO_VISIBLE_DOCS", req, List.of(), List.of());
            }

            // step4 目录路由（Phase1 降级全库，hook）
            step4DirectoryRouting(req.getQuery(), req.getKbId());

            // step5 dense 召回（强制 §6.1 WHERE）
            int maxL0 = req.getMaxL0() != null ? req.getMaxL0() : ragConfig.getMaxL0Candidates();
            List<RecallHit> l0 = step5DenseRecall(req.getKbId(), qHalf, scope, req.getDocTypes(), maxL0);
            if (l0.isEmpty()) {
                return finishAbstain(trace, budget, t0, "NO_DENSE_HITS", req, List.of(), List.of());
            }

            // step6 L2 候选 + rerank 代理
            boolean[] bm25Fallback = {false};
            List<L2Candidate> pool = step6L2Candidates(req.getQuery(), l0, req.getKbId(), bm25Fallback);
            if (pool.size() > ragConfig.getMaxRerankPairs()) {
                throw new IllegalStateException("B3 违规: rerank pool=" + pool.size()
                        + " > maxRerankPairs=" + ragConfig.getMaxRerankPairs());
            }
            List<L2Candidate> topK = pickTopK(pool, ragConfig.getMaxL2Read());
            trace.setL2LexicalFallback(bm25Fallback[0]);

            // step7 abstention（看 best 父L0 sim，非 rerankScore）
            double bestSim = topK.stream().mapToDouble(L2Candidate::parentL0Sim).max().orElse(0);
            if (bestSim < ragConfig.getAbstainThreshold()) {
                return finishAbstain(trace, budget, t0, "LOW_CONFIDENCE", req, l0, toEvidencePreview(topK));
            }

            // step8 evidence + 生成 + Citation
            List<Evidence> evidence = step8LoadEvidence(topK);
            if (evidence.isEmpty()) {
                return finishAbstain(trace, budget, t0, "NO_DENSE_HITS", req, l0, List.of());
            }
            EvidencePack pack = fitToBudget(evidence, cap);
            String answer = generate(req.getQuery(), pack, userId, false);
            List<Integer> cited = citationChecker.extractAndCheck(answer, pack.injectedIndexes());
            if (cited == null) {
                answer = generate(req.getQuery(), pack, userId, true);   // A1 重试一次
                cited = citationChecker.extractAndCheck(answer, pack.injectedIndexes());
                if (cited == null) {
                    return finishAbstain(trace, budget, t0, "CITATION_CHECK_FAIL", req, l0, toEvidencePreview(topK));
                }
            }

            trace.setCragVerdict("SUPPORTED");
            budget.setPromptTokens(TokenEstimator.estimate(pack.prompt()));
            RagRetrieveVO vo = buildVo(traceId, false, null, answer, cited, evidence,
                    pack.injected(), l0, budget, t0);
            writeTrace(trace, l0, pack.injected(), budget, "SUPPORTED", t0, req);
            // 缓存写入（仅 SUPPORTED；A2 abstain 各分支不写）
            if (answerCacheProps.isEnabled()) {
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
                        cacheSig, payload, nodeIds, hashes, bestSim, embedModel);
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
            // B4：query embed 一次（同模型，组内可比）
            KnowledgeBase kb0 = knowledgeBaseService.ensure(effectiveKbs.get(0));
            String embedModel = (kb0.getEmbeddingModel() == null || kb0.getEmbeddingModel().isBlank())
                    ? EMBED_MODEL_FALLBACK : kb0.getEmbeddingModel();
            float[] qVec = llmGateway.embed(query, embedModel);
            if (qVec.length != HalfVecUtil.DIM) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                        "query embedding 维度不匹配 expected=" + HalfVecUtil.DIM + " actual=" + qVec.length);
            }
            String qHalf = HalfVecUtil.toHalfVec(qVec);

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

            // step2 答案缓存（阶段4-B）：命中则跳过 step3-7 检索，回放缓存证据上下文
            if (answerCacheProps.isEnabled() && !validScopes.isEmpty()) {
                List<AnswerCacheService.KbScope> sigScopes = validScopes.stream()
                        .map(c -> new AnswerCacheService.KbScope(c.kbId, c.vs.allDocs, c.vs.docIds))
                        .toList();
                String cacheSig = answerCacheService.permissionSignature(sigScopes);
                Optional<CachedPayload> hit = answerCacheService.lookup(qHalf, userId, cacheSig);
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
                List<RecallHit> l0 = step5DenseRecall(kbId, qHalf, scope, null, maxL0);
                if (l0.isEmpty()) {
                    continue;
                }
                allL0.addAll(l0);
                List<L2Candidate> poolK = gatherL2Candidates(query, l0, kbId, bm25Fallback);
                if (poolK.size() > ragConfig.getMaxRerankPairs()) {
                    throw new IllegalStateException("B3 违规(per-kb): pool=" + poolK.size()
                            + " > maxRerankPairs=" + ragConfig.getMaxRerankPairs());
                }
                allPool.addAll(poolK);
            }

            trace.setL2LexicalFallback(bm25Fallback[0]);   // D1 trace：多 KB 合并 BM25 回退标记（NOT NULL 列）

            if (allL0.isEmpty()) {
                writeTraceMerged(trace, allL0, List.of(), budget, "NO_DENSE_HITS", t0, effectiveKbs, query, userId);
                return com.superprogrammer.knowledge.dto.EvidenceResult.abstain(traceId, "NO_DENSE_HITS", ABSTAIN_MSG);
            }

            // 全局 rerank（maxBm 在合并集归一，跨 KB 可比）
            double globalMaxBm = allPool.stream().map(L2Candidate::bm25Rank)
                    .filter(java.util.Objects::nonNull).mapToDouble(Double::doubleValue).max().orElse(0);
            List<L2Candidate> ranked = rerankWithBoost(allPool, globalMaxBm);
            List<L2Candidate> topK = pickTopK(ranked, ragConfig.getMaxL2Read());

            // A2 abstain（全局 best 父L0 sim）
            double bestSim = topK.stream().mapToDouble(L2Candidate::parentL0Sim).max().orElse(0);
            if (bestSim < ragConfig.getAbstainThreshold()) {
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

            writeTraceMerged(trace, allL0, pack.injected(), budget, "SUPPORTED", t0, effectiveKbs, query, userId);
            List<com.superprogrammer.knowledge.dto.RagRetrieveVO.CitationVO> citations = pack.injected().stream()
                    .map(e -> com.superprogrammer.knowledge.dto.RagRetrieveVO.CitationVO.builder()
                            .index(e.citationIndex()).documentId(e.documentId())
                            .title(e.title()).nodeId(e.nodeId()).build())
                    .toList();
            // 缓存写入（仅 SUPPORTED；A2 abstain 不写）
            if (answerCacheProps.isEnabled()) {
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
                        nodeIds, hashes, bestSim, embedModel);
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
            log.warn("写 rag_retrieval_logs 失败（不影响结果）: {}", e.getMessage());
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
            hits.add(new RecallHit(r.getNodeId(), r.getDocumentId(), r.getTitle(), dist, 1.0 - dist));
        }
        return hits;
    }

    /**
     * step6：单 KB — gather + 按 KB 内 maxBm 加 boost 排序（行为与重构前一致）。
     * R1：L2 不重嵌。B4：无额外 embed。BM25 仅 boost（'simple' 中文弱）。
     */
    private List<L2Candidate> step6L2Candidates(String query, List<RecallHit> l0,
                                                Long kbId, boolean[] bm25Fallback) {
        List<L2Candidate> gathered = gatherL2Candidates(query, l0, kbId, bm25Fallback);
        double maxBm = gathered.stream().map(L2Candidate::bm25Rank)
                .filter(java.util.Objects::nonNull).mapToDouble(Double::doubleValue).max().orElse(0);
        return rerankWithBoost(gathered, maxBm);
    }

    /**
     * gather：top-M L0 → top-D 文档 → L2 子节点 ∪ BM25 命中。
     * 返回候选（rerankScore=parentL0Sim 占位，未加 boost；bm25Rank 原值），供多 KB 合并后统一 rerank。
     */
    private List<L2Candidate> gatherL2Candidates(String query, List<RecallHit> l0,
                                                 Long kbId, boolean[] bm25Fallback) {
        int topM = Math.min(ragConfig.getDenseTopM(), l0.size());
        List<RecallHit> topMHits = l0.subList(0, topM);

        // top-D 文档（按 L0 sim 序取前 D 个不同 doc）
        LinkedLongSet topDDocs = new LinkedLongSet();
        for (RecallHit h : l0) {
            topDDocs.add(h.documentId());
            if (topDDocs.size() >= ragConfig.getDenseTopD()) {
                break;
            }
        }
        List<Long> topDDocIds = topDDocs.list();
        if (topDDocIds.isEmpty()) {
            return List.of();
        }

        // parentId→sim（全 L0 命中，供 BM25-only 候选回查父 sim）
        Map<Long, Double> parentSim = new LinkedHashMap<>();
        List<Long> topMParentIds = new ArrayList<>();
        for (RecallHit h : topMHits) {
            parentSim.put(h.nodeId(), h.cosineSim());
            topMParentIds.add(h.nodeId());
        }
        for (RecallHit h : l0) {
            parentSim.putIfAbsent(h.nodeId(), h.cosineSim());
        }

        // L2 子节点（parent-anchored），每文档 cap
        Map<Long, L2Candidate> byNode = new LinkedHashMap<>();
        int perDoc = ragConfig.getPerDocL2Cap();
        Map<Long, Integer> perDocCount = new LinkedHashMap<>();
        for (RagQueryRow.L2Row r : queryMapper.fetchL2Children(kbId, topMParentIds, topDDocIds)) {
            if (perDocCount.merge(r.getDocumentId(), 1, Integer::sum) > perDoc) {
                continue;
            }
            double psim = simOf(r.getParentId(), parentSim);
            byNode.put(r.getNodeId(), toCandidate(r, psim, null, false));
        }

        // BM25 命中并集
        List<RagQueryRow.L2Row> bm25 = queryMapper.bm25Hits(kbId, query, topDDocIds);
        for (RagQueryRow.L2Row r : bm25) {
            double psim = simOf(r.getParentId(), parentSim);
            boolean bmOnly = !byNode.containsKey(r.getNodeId());
            if (bmOnly) {
                bm25Fallback[0] = true;   // 纯 BM25（无父锚）候选进入 pool
            }
            byNode.merge(r.getNodeId(), toCandidate(r, psim, r.getBm25Rank(), bmOnly),
                    (a, b) -> b.bm25Rank() != null ? b : a);
        }
        return new ArrayList<>(byNode.values());
    }

    /**
     * 加 BM25 boost + 排序。maxBm 由调用方提供（单 KB=KB 内最大；多 KB=合并集全局最大，归一可比）。
     */
    private List<L2Candidate> rerankWithBoost(List<L2Candidate> candidates, double maxBm) {
        if (maxBm <= 0) {
            maxBm = 1;
        }
        double boostMax = ragConfig.getBm25BoostMax();
        List<L2Candidate> pool = new ArrayList<>(candidates.size());
        for (L2Candidate c : candidates) {
            double boost = c.bm25Rank() == null ? 0 : boostMax * (c.bm25Rank() / maxBm);
            pool.add(new L2Candidate(c.nodeId(), c.documentId(), c.parentId(), c.title(),
                    c.content(), c.contentHash(), c.parentL0Sim(), c.bm25Rank(),
                    c.parentL0Sim() + boost, c.bm25Only()));
        }
        pool.sort((a, b) -> Double.compare(b.rerankScore(), a.rerankScore()));
        return pool;
    }

    private List<L2Candidate> pickTopK(List<L2Candidate> pool, int k) {
        return pool.subList(0, Math.min(k, pool.size()));
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
                    c.contentHash(), l1.docType, l1.outline, l1.rules, idx, c.rerankScore()));
            idx++;
        }
        return out;
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
                .model(ragConfig.getChatModel())
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
                                        List<RecallHit> l0, List<Evidence> evidencePreview) {
        trace.setCragVerdict(verdict);
        RagRetrieveVO vo = RagRetrieveVO.builder()
                .traceId(trace.getTraceId())
                .abstained(true)
                .abstainReason(verdict)
                .answer(ABSTAIN_MSG)
                .citations(List.of())
                .candidatesL0(toRecallVOs(l0))
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
                                 RagRetrieveVO.TokenBudgetVO budget, long t0) {
        Map<Integer, Evidence> byIdx = new LinkedHashMap<>();
        for (Evidence e : injected) {
            byIdx.put(e.citationIndex(), e);
        }
        List<RagRetrieveVO.CitationVO> citations = new ArrayList<>();
        for (Integer i : cited) {
            Evidence e = byIdx.get(i);
            if (e != null) {
                citations.add(RagRetrieveVO.CitationVO.builder()
                        .index(i).documentId(e.documentId()).title(e.title()).nodeId(e.nodeId()).build());
            }
        }
        return RagRetrieveVO.builder()
                .traceId(traceId).abstained(abstained).abstainReason(reason).answer(answer)
                .citations(citations)
                .candidatesL0(toRecallVOs(l0))
                .evidenceL2(toEvidenceVOs(allEvidence))
                .tokenBudget(budget)
                .latencyMs(System.currentTimeMillis() - t0)
                .build();
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
            log.warn("写 rag_retrieval_logs 失败（不影响结果）: {}", e.getMessage());
        }
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
        return l;
    }

    // ============================ 小工具 ============================

    private List<Evidence> toEvidencePreview(List<L2Candidate> topK) {
        List<Evidence> out = new ArrayList<>();
        int idx = 1;
        for (L2Candidate c : topK) {
            out.add(new Evidence(c.nodeId(), c.documentId(), c.title(), c.content(),
                    c.contentHash(), null, null, null, idx, c.rerankScore()));
            idx++;
        }
        return out;
    }

    private List<RagRetrieveVO.RecallHitVO> toRecallVOs(List<RecallHit> l0) {
        return l0.stream().map(h -> RagRetrieveVO.RecallHitVO.builder()
                .nodeId(h.nodeId()).documentId(h.documentId()).title(h.title())
                .cosineDistance(h.cosineDistance()).cosineSimilarity(h.cosineSim()).build()).toList();
    }

    private List<RagRetrieveVO.EvidenceVO> toEvidenceVOs(List<Evidence> ev) {
        return ev.stream().map(e -> RagRetrieveVO.EvidenceVO.builder()
                .nodeId(e.nodeId()).documentId(e.documentId()).title(e.title())
                .content(e.content()).contentHash(e.contentHash()).docType(e.docType())
                .citationIndex(e.citationIndex()).rerankScore(e.rerankScore()).build()).toList();
    }

    private double simOf(Long parentId, Map<Long, Double> parentSim) {
        if (parentId == null) {
            return 0;
        }
        Double s = parentSim.get(parentId);
        return s == null ? 0 : s;
    }

    private L2Candidate toCandidate(RagQueryRow.L2Row r, double psim, Double bm25, boolean bmOnly) {
        return new L2Candidate(r.getNodeId(), r.getDocumentId(), r.getParentId(), r.getTitle(),
                r.getContent(), r.getContentHash(), psim, bm25, psim, bmOnly);
    }

    private record L1Outline(String docType, String outline, String rules) {
    }

    private L1Outline loadL1(Long docId) {
        RagQueryRow.L1Row row = queryMapper.fetchL1Metadata(docId);
        if (row == null || row.getL1Metadata() == null || row.getL1Metadata().isBlank()) {
            return new L1Outline(row == null ? null : row.getDocType(), null, null);
        }
        try {
            L1Metadata l1 = objectMapper.readValue(row.getL1Metadata(), L1Metadata.class);
            String outline = l1.getOutline() == null ? null : String.join("；", l1.getOutline());
            String rules = l1.getImportantRules() == null ? null : String.join("；", l1.getImportantRules());
            return new L1Outline(row.getDocType(), outline, rules);
        } catch (Exception e) {
            log.warn("L1 元数据解析失败 docId={}: {}", docId, e.getMessage());
            return new L1Outline(row.getDocType(), null, null);
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
                .title(r.getTitle()).nodeId(r.getNodeId()).build()).toList();
    }

    /** RagRetrieveVO.CitationVO → CachedPayload.CitationRef（写入缓存）。 */
    private List<CachedPayload.CitationRef> toCitationRefs(List<RagRetrieveVO.CitationVO> citations) {
        if (citations == null || citations.isEmpty()) {
            return List.of();
        }
        return citations.stream().map(c -> CachedPayload.CitationRef.builder()
                .index(c.getIndex()).documentId(c.getDocumentId())
                .title(c.getTitle()).nodeId(c.getNodeId()).build()).toList();
    }
}
