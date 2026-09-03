package com.superprogrammer.knowledge.context;

import com.superprogrammer.knowledge.query.QueryPlan;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * C3 覆盖判定（WP2 Step2）：必达子意图=EXACT filter 值（无 filter 空→单轮即覆盖）/
 * 大小写不敏感包含判定 / missing 保序去重 / LLM 子意图合并面。
 */
class CoverageVerifierTest {

    private final CoverageVerifier v = new CoverageVerifier();

    private record Cand(String title, String content) implements CoverageVerifier.CandidateText {
    }

    @Test
    void requiredFromFilters() {
        QueryPlan plan = new QueryPlan("EXACT", "DIRECT",
                Map.of("version", "V2.1", "article", "第十条"), List.of("EXACT", "SPARSE"),
                false, false, false);
        assertEquals(List.of("V2.1", "第十条"), v.requiredFor(plan));   // 排序保确定（Map.copyOf 无序）
    }

    @Test
    void requiredEmpty_withoutFilters() {
        QueryPlan plan = new QueryPlan("SEMANTIC", "DIRECT", Map.of(),
                List.of("DENSE", "SPARSE"), false, false, true);
        assertTrue(v.requiredFor(plan).isEmpty());
        assertTrue(v.requiredFor(null).isEmpty());
    }

    @Test
    void coveredBy_caseInsensitive_titleOrContent() {
        List<String> required = List.of("V2.1");
        assertTrue(v.coveredBy(List.of(new Cand(null, "本制度适用版本 v2.1")), required).contains("V2.1"));
        assertTrue(v.coveredBy(List.of(new Cand("差旅制度 V2.1", null)), required).contains("V2.1"));
    }

    @Test
    void missing_dedupOrdered() {
        assertEquals(List.of("B"), v.missing(List.of("A", "B"), Set.of("A")));
        assertTrue(v.missing(List.of(), Set.of("A")).isEmpty());
        assertTrue(v.missing(null, null).isEmpty());
    }

    @Test
    void requiredFrom_mergesLlmSubIntents() {
        QueryPlan plan = new QueryPlan("EXACT", "DIRECT",
                Map.of("version", "V2.1"), List.of("EXACT"), false, false, false);
        assertEquals(List.of("V2.1", "报销流程"), v.requiredFrom(plan, List.of("V2.1", "报销流程", "V2.1")));
        assertEquals(List.of("V2.1"), v.requiredFrom(plan, null));
    }
}
