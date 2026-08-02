package com.superprogrammer.knowledge.service.internal;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Citation 硬校验（v6 A1，post-gen，零 LLM，确定性）。
 *
 * 扫描答案中的 [n] 引用，要求全部 ∈ 注入集合（{1..K} 中实际装载的索引）。
 * 剥代码块后扫描（代码块里的 [n] 不是证据引用）。
 * 校验失败返回 null（调用方决定重生成/abstain），成功返回去重有序引用索引列表。
 */
@Component
public class CitationChecker {

    /** [n]，n 1-2 位，前后非单词字符（避免 a[1]b / [12ab]）。 */
    private static final Pattern CITATION = Pattern.compile("(?<!\\w)\\[(\\d{1,2})](?!\\w)");

    /**
     * @param answer          生成的答案
     * @param injectedIndexes 实际注入证据的合法索引集合（如 {1,2,3}）
     * @return 去重有序的引用索引；引用为空返回空列表；存在越界引用返回 null（A1 违规）
     */
    public List<Integer> extractAndCheck(String answer, Set<Integer> injectedIndexes) {
        if (answer == null || answer.isBlank()) {
            return List.of();
        }
        String cleaned = stripCodeFences(answer);
        Set<Integer> citedOrdered = new LinkedHashSet<>();
        Matcher m = CITATION.matcher(cleaned);
        while (m.find()) {
            citedOrdered.add(Integer.parseInt(m.group(1)));
        }
        if (citedOrdered.isEmpty()) {
            return List.of();   // 无引用，由调用方判定（可能 abstain 话术）
        }
        // A1：任一引用 ∉ 注入集合 → 违规
        for (Integer c : citedOrdered) {
            if (!injectedIndexes.contains(c)) {
                return null;
            }
        }
        return new ArrayList<>(new TreeSet<>(citedOrdered));
    }

    /** 剥 markdown 代码块（```...```）—— 其内的 [n] 不算引用。 */
    private String stripCodeFences(String text) {
        return text.replaceAll("(?s)```.*?```", " ");
    }
}
