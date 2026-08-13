package com.superprogrammer.knowledge.query;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class QueryPlannerTest {
    private final QueryPlanner planner = new QueryPlanner();

    @Test void exactVersionAndDateQuestionsUseRulesWithoutLlm() {
        QueryPlan plan = planner.plan("请查 2025-03-01 发布的 V2.3 第十条原文");
        assertEquals("EXACT", plan.queryType());
        assertTrue(plan.strategies().contains("EXACT"));
        assertFalse(plan.requiresLlmAnalysis());
        assertEquals("V2.3", plan.filters().get("version"));
    }

    @Test void comparisonUsesMultiEvidenceShape() {
        QueryPlan plan = planner.plan("比较方案A和方案B的差异");
        assertEquals("COMPARISON", plan.queryType());
        assertEquals("MULTI_EVIDENCE", plan.answerShape());
        assertTrue(plan.exhaustive());
    }
}
