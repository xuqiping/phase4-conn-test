package com.superprogrammer.knowledge.service.internal;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RrfFusionTest {

    @Test
    void fuse_rank1InEachList_scoresHighest() {
        // 两条有序列表，key "a" 都在 rank1 → rrf(a) = 2 * 1/(60+1)
        Map<String, Double> scores = RrfFusion.fuse(List.of(
                List.of("a", "b"),
                List.of("a", "c")), 60);

        double expected = 2.0 * (1.0 / 61.0);
        assertEquals(expected, scores.get("a"), 1e-9);
        assertEquals(1.0 / 62.0, scores.get("b"), 1e-9);   // rank2 list1
        assertEquals(1.0 / 62.0, scores.get("c"), 1e-9);   // rank2 list2
    }

    @Test
    void sortByScoreDesc_bestFirst() {
        Map<String, Double> scores = RrfFusion.fuse(List.of(
                List.of("a", "b", "c"),
                List.of("c", "a")), 60);

        List<String> ranked = RrfFusion.sortByScoreDesc(scores);
        // "a"(rank1+rank2) 与 "c"(rank3+rank1) 比，"a" 分更高
        assertEquals("a", ranked.get(0));
        assertEquals(3, ranked.size());
    }

    @Test
    void fuseWeighted_appliesChannelWeight() {
        Map<String, Double> scores = RrfFusion.fuseWeighted(List.of(
                new RrfFusion.WeightedList<>(List.of("a"), 2.0),
                new RrfFusion.WeightedList<>(List.of("a"), 0.5)), 60);

        assertEquals(2.0 / 61.0 + 0.5 / 61.0, scores.get("a"), 1e-9);
    }

    @Test
    void fuse_emptyListsSafe() {
        // List.of 不允许 null 元素 → 用 Arrays.asList
        Map<String, Double> scores = RrfFusion.fuse(java.util.Arrays.asList(List.of(), null), 60);
        assertTrue(scores.isEmpty());
    }
}
