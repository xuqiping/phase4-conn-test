package com.superprogrammer.knowledge.retrieval;

import com.superprogrammer.knowledge.context.CoverageVerifier;
import com.superprogrammer.knowledge.query.QueryPlan;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * C3 有界循环检索编排（WP2 Step2，规格 §5.1）：round0 之外的补充轮。
 *
 * <p>循环结构：round0（现有完整管道，调用方已跑完）→ CoverageVerifier 判缺口
 * （必达子意图 vs 候选覆盖）→ 缺口则补充 query（未覆盖 filter 值本身——锚点即 query，
 * 语义邻域与原 query 不同；继承原 KB/权限/版本范围，由回调闭包保证）→ 回调补召回
 * → 候选并集（by nodeId 去重，score 取高者由调用方统一重排）→ 再判 → 轮次耗尽跳出。
 *
 * <p>零回归门：required 空（无 filter 且无 LLM 子意图）→ 直接返回，rounds=0，
 * 零额外对象分配零 LLM 调用——覆盖场景行为与基线逐字节一致。
 *
 * <p>无进展守卫：补轮零新候选即停（继续跑只烧钱不收敛）。去重：补充 query 与已用集
 * （含 round0 原 query）hash 比对，同 query 不重复检索。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IterativeRetrievalOrchestrator {

    private final CoverageVerifier coverageVerifier = new CoverageVerifier();

    /** 编排结果：轮次/用过的补充 query/并集新增候选/仍未覆盖子意图。 */
    public record Outcome(int roundsExecuted, List<String> supplementQueriesUsed,
                          List<RetrievalCandidate> newCandidates, List<String> stillMissing) {
    }

    /**
     * @param query          原 query（round0 已用，进已用集防重）
     * @param plan           round0 QueryPlan（规则子意图=filter 值）
     * @param round0Candidates round0 候选（覆盖判定起点）
     * @param maxRounds      含 round0 的总轮数上限（1=单轮基线）
     * @param perRoundCap    每轮补充 query 上限
     * @param llmSubIntents  LLM 子意图（Step4 供给；null/空=规则版）
     * @param recall         补召回回调（入参=补充 query；须继承 round0 的 KB/权限/版本范围）
     */
    public Outcome expand(String query, QueryPlan plan, List<RetrievalCandidate> round0Candidates,
                          int maxRounds, int perRoundCap, List<String> llmSubIntents,
                          java.util.function.Function<String, List<RetrievalCandidate>> recall) {
        List<String> required = coverageVerifier.requiredFrom(plan, llmSubIntents);
        if (required.isEmpty() || maxRounds <= 1) {
            return new Outcome(0, List.of(), List.of(), List.of());
        }
        Set<String> covered = coveredOf(round0Candidates, required);
        List<String> missing = coverageVerifier.missing(required, covered);
        if (missing.isEmpty()) {
            return new Outcome(0, List.of(), List.of(), List.of());
        }

        Set<String> usedQueries = new HashSet<>();
        usedQueries.add(query);
        List<String> usedSupplement = new ArrayList<>();
        List<RetrievalCandidate> fresh = new ArrayList<>();
        Set<String> seenNodeIds = new HashSet<>();
        for (RetrievalCandidate c : round0Candidates) {
            seenNodeIds.add(c.id());
        }
        int rounds = 0;
        long t0 = System.currentTimeMillis();
        while (rounds < maxRounds - 1 && !missing.isEmpty()) {
            List<String> batch = missing.stream()
                    .filter(q -> !usedQueries.contains(q))
                    .limit(Math.max(1, perRoundCap))
                    .toList();
            if (batch.isEmpty()) {
                break;   // 剩余缺口全部查过 → 无新 query 可用
            }
            List<RetrievalCandidate> roundFresh = new ArrayList<>();
            for (String suppQuery : batch) {
                usedQueries.add(suppQuery);
                usedSupplement.add(suppQuery);
                List<RetrievalCandidate> hits = recall.apply(suppQuery);
                if (hits == null) {
                    continue;
                }
                for (RetrievalCandidate c : hits) {
                    if (c != null && c.id() != null && seenNodeIds.add(c.id())) {
                        roundFresh.add(c);
                    }
                }
            }
            rounds++;
            if (roundFresh.isEmpty()) {
                log.info("C3 补充轮无新候选即停 rounds={} missing={}", rounds, missing);
                break;   // 无进展守卫
            }
            fresh.addAll(roundFresh);
            covered.addAll(coveredOf(roundFresh, required));
            missing = coverageVerifier.missing(required, covered);
        }
        if (rounds > 0) {
            log.info("C3 多轮检索完成 rounds={} supplementQueries={} newCandidates={} stillMissing={} costMs={}",
                    rounds, usedSupplement.size(), fresh.size(), missing.size(),
                    System.currentTimeMillis() - t0);
        }
        return new Outcome(rounds, usedSupplement, fresh, missing);
    }

    private Set<String> coveredOf(List<RetrievalCandidate> candidates, List<String> required) {
        return coverageVerifier.coveredBy(candidates == null ? List.of() : candidates, required);
    }
}
