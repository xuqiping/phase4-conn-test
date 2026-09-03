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

    // ---- C7 GLOBAL（WP4 Step2）：库级聚合 → map-reduce 分支 ----

    @Test void globalScopePlusIntentClassifiedGlobal() {
        assertEquals("GLOBAL", planner.plan("总结一下全库的主要主题").queryType());
        assertEquals("OVERVIEW", planner.plan("总结一下全库的主要主题").answerShape());
        assertEquals("GLOBAL", planner.plan("这个库主要讲什么").queryType());
        assertEquals("GLOBAL", planner.plan("列出全库所有文档的主题清单").queryType());   // 库级聚合优先于 LIST
        assertEquals("GLOBAL", planner.plan("整个知识库的趋势是什么").queryType());
    }

    @Test void localSummaryQuestionNotGlobal() {
        // 「总结」无库级范围词 → 不进 GLOBAL（又慢又泛的误判防护）
        assertNotEquals("GLOBAL", planner.plan("总结一下报销流程的步骤").queryType());
        assertNotEquals("GLOBAL", planner.plan("这个方案的要点是什么").queryType());
    }

    @Test void exactAnchorWithoutIntentStillExact() {
        // 锚点问题无聚合意图词（「说了什么」不在意图词表）→ 仍走精确检索
        QueryPlan plan = planner.plan("全库中V2.1文档的第十条说了什么");
        assertEquals("EXACT", plan.queryType());
    }

    // ---- WP4 Step3：混合问题（范围词+意图词+锚点）→ GLOBAL 主分支，锚点留 filters ----

    @Test void hybridGlobalPlusAnchorGlobalWithFiltersRetained() {
        QueryPlan plan = planner.plan("总结全库，V2.1第十条原文是什么");
        assertEquals("GLOBAL", plan.queryType());
        // 锚点不丢：作为细节跟进轮的「必达子意图」保留在 filters
        assertEquals("V2.1", plan.filters().get("version"));
        assertEquals("第十条", plan.filters().get("article"));
    }
}
