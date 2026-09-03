package com.superprogrammer.knowledge.retrieval;

import com.superprogrammer.knowledge.query.QueryPlan;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C3 有界循环编排（WP2 Step2）：required 空→rounds=0 零调用 / maxRounds=1=基线 /
 * 缺口→补轮覆盖 / 轮次耗尽仍缺 / 去重（同 nodeId/同 query）/ 无进展守卫。
 */
class IterativeRetrievalOrchestratorTest {

    private final IterativeRetrievalOrchestrator orchestrator = new IterativeRetrievalOrchestrator();

    private static QueryPlan exactPlan() {
        return new QueryPlan("EXACT", "DIRECT",
                Map.of("version", "V2.1"), List.of("EXACT", "SPARSE"), false, false, false);
    }

    private static RetrievalCandidate cand(long nodeId, String title, String content) {
        return new RetrievalCandidate(String.valueOf(nodeId), nodeId, 99L, "TEST", 0.5, title, content);
    }

    @Test
    void noRequired_zeroRounds_zeroRecall() {
        var out = orchestrator.expand("q", exactPlanNoFilter(), List.of(cand(1, "t", "c")),
                2, 3, null, q -> { throw new IllegalStateException("不应触达召回"); });
        assertEquals(0, out.roundsExecuted());
        assertTrue(out.newCandidates().isEmpty());
    }

    private static QueryPlan exactPlanNoFilter() {
        return new QueryPlan("SEMANTIC", "DIRECT", Map.of(), List.of("DENSE"), false, false, true);
    }

    @Test
    void maxRoundsOne_baselineNoLoop() {
        var out = orchestrator.expand("差旅 V2.1", exactPlan(),
                List.of(cand(1, "旧版", "无锚点")), 1, 3, null,
                q -> { throw new IllegalStateException("maxRounds=1 不应补轮"); });
        assertEquals(0, out.roundsExecuted());
    }

    @Test
    void coveredRound0_zeroRounds() {
        var out = orchestrator.expand("差旅 V2.1", exactPlan(),
                List.of(cand(1, "差旅制度 V2.1", "内容")), 2, 3, null,
                q -> { throw new IllegalStateException("已覆盖不应补轮"); });
        assertEquals(0, out.roundsExecuted());
    }

    @Test
    void missing_supplementCovers() {
        // round0 未锚到 V2.1；补充轮召回含 V2.1 的新节点 → 覆盖收口
        var out = orchestrator.expand("差旅 V2.1", exactPlan(),
                List.of(cand(1, "差旅制度", "通用内容")), 2, 3, null,
                q -> List.of(cand(2, "差旅制度 V2.1", "V2.1 版条款")));
        assertEquals(1, out.roundsExecuted());
        assertEquals(List.of("V2.1"), out.supplementQueriesUsed());
        assertEquals(1, out.newCandidates().size());
        assertEquals(2L, out.newCandidates().get(0).nodeId());
        assertTrue(out.stillMissing().isEmpty());
    }

    @Test
    void roundsExhausted_stillMissing() {
        // maxRounds=2 → 至多 1 补轮；召回仍不含锚点 → 仍缺但不再跑
        AtomicInteger calls = new AtomicInteger();
        var out = orchestrator.expand("差旅 V2.1", exactPlan(),
                List.of(cand(1, "差旅制度", "通用内容")), 2, 3, null,
                q -> {
                    calls.incrementAndGet();
                    return List.of(cand(2, "别的内容", "还是没有"));
                });
        assertEquals(1, out.roundsExecuted());
        assertEquals(1, calls.get());
        assertEquals(List.of("V2.1"), out.stillMissing());
    }

    @Test
    void duplicateNodes_deduped() {
        // 补充轮返回 round0 已有 nodeId → 不进新候选（并集 by nodeId）
        var out = orchestrator.expand("差旅 V2.1", exactPlan(),
                List.of(cand(1, "差旅制度 V2.1", "内容")), 2, 3, null,
                q -> List.of(cand(1, "重复", "dup")));
        assertEquals(0, out.roundsExecuted());   // 覆盖后无缺口，未进循环
    }

    @Test
    void noProgress_breaksEarly() {
        // 补轮零新候选 → 无进展守卫停（不再烧第二轮）
        AtomicInteger calls = new AtomicInteger();
        var out = orchestrator.expand("差旅 V2.1", exactPlan(),
                List.of(cand(1, "t", "c")), 3, 3, null,
                q -> {
                    calls.incrementAndGet();
                    return List.of();
                });
        assertEquals(1, out.roundsExecuted());
        assertEquals(1, calls.get());   // 3 轮上限但零进展即停
        assertEquals(List.of("V2.1"), out.stillMissing());
    }

    @Test
    void llmSubIntents_extendRequired() {
        // LLM 子意图（Step4 面）：round0 未覆盖「报销流程」→ 以它为补充 query
        var out = orchestrator.expand("差旅报销", exactPlan(),
                List.of(cand(1, "差旅制度", "V2.1")), 2, 3, List.of("报销流程"),
                q -> List.of(cand(2, "报销流程", "发票与审批")));
        assertEquals(1, out.roundsExecuted());
        assertEquals(List.of("报销流程"), out.supplementQueriesUsed());
        assertTrue(out.stillMissing().isEmpty());
    }
}
