package com.superprogrammer.knowledge.opensearch;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IndexAliasServiceTest {

    @Test
    void switchPlanAtomicallyMovesReadAndWriteAliases() {
        IndexAliasService service = new IndexAliasService();

        List<IndexAliasService.AliasAction> actions = service.switchPlan(
                42L, "kb-42-chunks-old", "kb-42-chunks-new");

        assertEquals(List.of(
                new IndexAliasService.AliasAction("REMOVE", "kb-42-chunks-old", "kb-42-chunks-read", false),
                new IndexAliasService.AliasAction("REMOVE", "kb-42-chunks-old", "kb-42-chunks-write", false),
                new IndexAliasService.AliasAction("ADD", "kb-42-chunks-new", "kb-42-chunks-read", false),
                new IndexAliasService.AliasAction("ADD", "kb-42-chunks-new", "kb-42-chunks-write", true)
        ), actions);
    }

    @Test
    void rollbackIsTheReverseAtomicSwitch() {
        IndexAliasService service = new IndexAliasService();

        assertEquals(service.switchPlan(42L, "new", "old"), service.rollbackPlan(42L, "new", "old"));
    }
}
