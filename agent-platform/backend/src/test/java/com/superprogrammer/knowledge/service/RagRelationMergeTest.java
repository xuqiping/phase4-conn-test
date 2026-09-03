package com.superprogrammer.knowledge.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * C1 step6.5 合并序 + MAY 阈值过滤（WP1 Step2，规格 §3.2.5/§3.2.6）：
 * 必须引用 > 原始命中 > 按需引用；预算挤占从 MAY 开始；无边零回归（原样引用返回）。
 */
class RagRelationMergeTest {

    private static RagRetrievalService.L2Candidate cand(Long nodeId, double score) {
        return new RagRetrievalService.L2Candidate(nodeId, nodeId / 10, nodeId / 100,
                "t" + nodeId, "c" + nodeId, "h" + nodeId, score, null, score, false, 0);
    }

    @Test
    void mergeOrder_mustFirstThenOriginalThenMay() {
        List<RagRetrievalService.L2Candidate> original = List.of(cand(1L, 0.9), cand(2L, 0.8));
        List<RagRetrievalService.L2Candidate> must = List.of(cand(91L, 0.901));
        List<RagRetrievalService.L2Candidate> may = List.of(cand(92L, 0.85));

        List<RagRetrievalService.L2Candidate> merged =
                RagRetrievalService.mergeRelationCandidates(original, must, may);

        assertEquals(List.of(91L, 1L, 2L, 92L),
                merged.stream().map(RagRetrievalService.L2Candidate::nodeId).toList());
    }

    /** 零回归不变式：无 MUST/MAY → 返回原列表引用（fitToBudget/编号路径 byte-identical）。 */
    @Test
    void noRelation_returnsOriginalReference() {
        List<RagRetrievalService.L2Candidate> original = List.of(cand(1L, 0.9));
        assertSame(original, RagRetrievalService.mergeRelationCandidates(original, List.of(), List.of()));
        assertSame(original, RagRetrievalService.mergeRelationCandidates(original, null, null));
    }

    /** nodeId 去重防御：MUST 与原始命中撞 nodeId 时只保留 MUST 位（前置）。 */
    @Test
    void merge_dedupByNodeId() {
        List<RagRetrievalService.L2Candidate> original = List.of(cand(1L, 0.9), cand(2L, 0.8));
        List<RagRetrievalService.L2Candidate> must = List.of(cand(2L, 0.901));   // 与原始 2 撞

        List<RagRetrievalService.L2Candidate> merged =
                RagRetrievalService.mergeRelationCandidates(original, must, List.of());

        assertEquals(List.of(2L, 1L),
                merged.stream().map(RagRetrievalService.L2Candidate::nodeId).toList());
        // 保留 MUST 的分（0.901），证明保留的是 MUST 位而非原始位
        assertEquals(0.901, merged.get(0).rerankScore());
    }

    /** MAY 阈值过滤：重打分 ≥ 原始 topK 最低分才进（规格 §3.2.5「能过阈值才进」）。 */
    @Test
    void mayThreshold_keepsOnlyAboveFloor() {
        List<RagRetrievalService.L2Candidate> ranked =
                List.of(cand(91L, 0.85), cand(92L, 0.79), cand(93L, 0.80));

        List<RagRetrievalService.L2Candidate> kept =
                RagRetrievalService.keepMayAboveThreshold(ranked, 0.80);

        assertEquals(List.of(91L, 93L),
                kept.stream().map(RagRetrievalService.L2Candidate::nodeId).toList());
    }
}
