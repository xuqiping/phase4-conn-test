package com.superprogrammer.knowledge.service.internal;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CitationChecker A1 正则机械测（v6 A1，零 LLM，确定性）。
 * 覆盖：代码块剥离、[n] 抽取、越界→null、去重升序、blank→空、a[1]b/[123]/[1a] 拒。
 */
class CitationCheckerTest {

    private final CitationChecker checker = new CitationChecker();

    @Test
    void singleValidCitation() {
        List<Integer> result = checker.extractAndCheck("答案见 [1]。", Set.of(1, 2, 3));
        assertEquals(List.of(1), result);
    }

    @Test
    void multipleCitations_dedupAndSorted() {
        // 出现顺序 [2],[1],[2] → TreeSet 升序去重 [1,2]
        List<Integer> result = checker.extractAndCheck("见 [2] 和 [1]，再 [2]", Set.of(1, 2, 3));
        assertEquals(List.of(1, 2), result);
    }

    @Test
    void a1_outOfRangeCitation_returnsNull() {
        // [5] ∉ {1,2,3} → A1 违规
        assertNull(checker.extractAndCheck("引用 [5] 越界", Set.of(1, 2, 3)));
    }

    @Test
    void noCitation_returnsEmpty() {
        assertEquals(List.of(), checker.extractAndCheck("无引用的答案", Set.of(1, 2)));
    }

    @Test
    void blankAnswer_returnsEmpty() {
        assertEquals(List.of(), checker.extractAndCheck("", Set.of(1)));
        assertEquals(List.of(), checker.extractAndCheck("   ", Set.of(1)));
        assertEquals(List.of(), checker.extractAndCheck(null, Set.of(1)));
    }

    @Test
    void codeFenceCitations_stripped() {
        // 代码块内的 [1] 不算引用；块外 [2] 算
        String answer = "```\n[1] code ref\n```\n真正引用 [2]";
        assertEquals(List.of(2), checker.extractAndCheck(answer, Set.of(1, 2)));
    }

    @Test
    void codeFenceOnlyCitation_becomesEmpty() {
        String answer = "```\n[1] only in code\n```";
        assertEquals(List.of(), checker.extractAndCheck(answer, Set.of(1)));
    }

    @Test
    void rejectWordAdjacentBrackets() {
        // a[1]b：前有单词字符 → (?<!\w) 失败 → 不匹配
        assertEquals(List.of(), checker.extractAndCheck("a[1]b", Set.of(1)));
    }

    @Test
    void rejectThreeDigitBracket() {
        // [123]：\d{1,2} 后须紧跟 ]，3 位数不匹配
        assertEquals(List.of(), checker.extractAndCheck("[123]", Set.of(1, 12, 123)));
    }

    @Test
    void rejectNonDigitSuffix() {
        // [1a]：1 后须紧跟 ]，遇 a 失败
        assertEquals(List.of(), checker.extractAndCheck("[1a]", Set.of(1)));
    }

    @Test
    void twoDigitCitationAccepted() {
        // [12] 合法（2 位），须 ∈ 注入集
        assertEquals(List.of(12), checker.extractAndCheck("见 [12]", Set.of(1, 12)));
        assertNull(checker.extractAndCheck("见 [12]", Set.of(1, 2)));  // 12 越界
    }
}
