package com.superprogrammer.knowledge.context;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 边界邻近扩展纯函数（WP2 Step3）：授权过滤/保序去重/selected 前置。
 */
class NeighborExpanderTest {

    private final NeighborExpander expander = new NeighborExpander();

    @Test
    void expandsAdjacentInSelectedOrder() {
        var out = expander.expand(List.of(11L, 31L),
                Map.of(11L, List.of(12L), 31L, List.of(30L, 32L)),
                Set.of(12L, 30L, 32L));
        // selected 保序在前，邻居随后按 selected 遍历序追加
        assertEquals(List.of(11L, 31L, 12L, 30L, 32L), out);
    }

    @Test
    void unauthorizedNeighbor_filtered() {
        var out = expander.expand(List.of(11L), Map.of(11L, List.of(12L, 13L)), Set.of(13L));
        assertEquals(List.of(11L, 13L), out);
    }

    @Test
    void noAdjacency_returnsSelected() {
        var out = expander.expand(List.of(11L), Map.of(), Set.of(12L));
        assertEquals(List.of(11L), out);
    }
}
