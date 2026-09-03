package com.superprogrammer.knowledge.context;

import com.superprogrammer.knowledge.query.QueryPlan;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * C3 覆盖判定（WP2 Step2 改造激活，规格 §5.1）：query 的「必达子意图」是否已被候选证据覆盖。
 *
 * <p>规则版口径（保守，保零回归门）：必达子意图仅来自 QueryPlan.filters 的**精确值**
 * （版本号/日期/条款号——query 里明示的锚点，证据不含 = 检索没锚到）。
 * 其余 queryType（SEMANTIC/PROCEDURE/…）无必达子意图 → required 空 → 一轮即覆盖（rounds=0）。
 * LLM 版子意图（WP2 Step4 LlmQueryPlanner 产出）后续经 {@link #requiredFrom} 注入同一判定面。
 *
 * <p>覆盖判定=候选 title/content 大小写不敏感包含该值（版本 v2.1/V2.1 同算）。
 */
public class CoverageVerifier {

    /** 必达子意图：EXACT 类 filter 值（version/date/article）。无 filter → 空 → 单轮即覆盖。
     *  输出排序保确定（QueryPlan 的 Map.copyOf 不保插入序，补轮 batch 取前 N 须稳定）。 */
    public List<String> requiredFor(QueryPlan plan) {
        if (plan == null || plan.filters() == null || plan.filters().isEmpty()) {
            return List.of();
        }
        Set<String> required = new LinkedHashSet<>();
        for (String v : plan.filters().values()) {
            if (v != null && !v.isBlank()) {
                required.add(v.trim());
            }
        }
        return required.stream().sorted().toList();
    }

    /** LLM 子意图注入面（Step4）：外部子意图列表 + 规则 filter 值合并（LLM 关/降级时仅规则部分）。 */
    public List<String> requiredFrom(QueryPlan plan, List<String> llmSubIntents) {
        List<String> rule = requiredFor(plan);
        if (llmSubIntents == null || llmSubIntents.isEmpty()) {
            return rule;
        }
        Set<String> merged = new LinkedHashSet<>(rule);
        merged.addAll(llmSubIntents);
        return new ArrayList<>(merged);
    }

    /** 候选证据已覆盖的子意图集（title/content 大小写不敏感包含）。 */
    public Set<String> coveredBy(List<? extends CandidateText> candidates, List<String> required) {
        Set<String> covered = new LinkedHashSet<>();
        if (required == null || required.isEmpty() || candidates == null) {
            return covered;
        }
        for (String term : required) {
            String needle = term.toLowerCase(Locale.ROOT);
            for (CandidateText c : candidates) {
                String title = c.title() == null ? "" : c.title().toLowerCase(Locale.ROOT);
                String content = c.content() == null ? "" : c.content().toLowerCase(Locale.ROOT);
                if (title.contains(needle) || content.contains(needle)) {
                    covered.add(term);
                    break;
                }
            }
        }
        return covered;
    }

    /** 未覆盖子意图（保序去重）。 */
    public List<String> missing(List<String> required, Set<String> covered) {
        if (required == null || required.isEmpty()) {
            return List.of();
        }
        return required.stream().filter(k -> !covered.contains(k)).distinct().toList();
    }

    /** 覆盖判定只看文本（L2Candidate/补轮候选同面）。 */
    public interface CandidateText {
        String title();
        String content();
    }
}
