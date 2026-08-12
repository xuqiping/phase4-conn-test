package com.superprogrammer.chat.service.internal;

import com.superprogrammer.chat.entity.MemorySummaryCoverage;
import com.superprogrammer.chat.entity.MemoryTurn;
import com.superprogrammer.chat.mapper.MemorySummaryCoverageMapper;
import com.superprogrammer.chat.mapper.MemoryTurnMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 计划12 · D-5 · 召回 ⑥ 拼增量流水账（总体设计 §3.3 ⑥ + §6 向量 14 + 性能「防 N+1」）。
 * <p>
 * <b>二期 P1（V67，FR-006）</b>：turns 纯个人域——本类只拼本人 turns（{@code user_id=self}），
 * 项目侧记忆由 ①.5 条目合流（{@link MemoryEntryRecallService}）承担；一期项目 turns 取数
 * （readableAuthors ACL + 离职开关过滤）随项目挂载列 DROP 整体下线。
 * <p>
 * <b>allCovered 严格策略</b>（统一按 coverage(user_id=self) 判）：
 * <ul>
 *   <li>该 turn 贴的每个 tag_id 都有 coverage 行 → 跳原文（已被自己总结吃进，走 D-4 summary 即可）。</li>
 *   <li>某 tag 未覆盖 → 拼该 turn 原文（l1/l2，D-6 装配格式化）。</li>
 *   <li>turn 无 tag_id → 拼原文（保守不丢内容，无 tag 无 summary 能覆盖）。</li>
 * </ul>
 * 召回恒只认 {@code gen_done=true} 的 turn（mapper SQL 限定，raw 不参与）。
 * <p>
 * <b>防 N+1</b>（性能预案）：批量 {@code WHERE turn_id IN (...)} 一次取全部 coverage（{@link MemorySummaryCoverageMapper#findByUserAndTurns}）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryTurnPatcher {

    private final MemoryTurnMapper turnMapper;
    private final MemorySummaryCoverageMapper coverageMapper;

    /**
     * 收集 scope 内「未全覆盖、需拼原文」的流水账（二期 P1：仅本人个人域）。
     *
     * @param scope  召回 scope
     * @param userId 召回者
     * @return 未覆盖 turns（D-6 装配拼 l1/l2）；空 scope 或无未覆盖 → 空表
     */
    public List<MemoryTurn> collectUncovered(RecallScope scope, Long userId) {
        if (scope.isEmpty() || !scope.personalOn()) {
            return List.of();
        }
        RecallTimeWindow tw = scope.timeWindow();
        List<MemoryTurn> turns = turnMapper.findPersonalRecallableTurns(
                userId, scope.direction().name(), tw.start(), tw.end(), tw.relativeDays());
        if (turns == null || turns.isEmpty()) {
            return List.of();
        }

        // 批量取 coverage 防 N+1（性能预案：一次 IN 查询）
        List<Long> turnIds = turns.stream().map(MemoryTurn::getId).toList();
        List<MemorySummaryCoverage> coverages = coverageMapper.findByUserAndTurns(userId, turnIds);
        Set<String> coveredKeys = coverages.stream()
                .map(c -> c.getTurnId() + ":" + c.getTagId())
                .collect(Collectors.toSet());

        List<MemoryTurn> uncovered = turns.stream()
                .filter(t -> !allCovered(t, coveredKeys))
                .toList();
        log.debug("patcher userId={} 候选 {} → coverage {} → 未覆盖 {}（allCovered 跳 {}）",
                userId, turns.size(), coverages.size(), uncovered.size(), turns.size() - uncovered.size());
        return uncovered;
    }

    /** allCovered 严格：turn.tag_ids 每个 tag 都有 coverage 行 → true（跳原文）；空 tag → false（拼原文）。 */
    private boolean allCovered(MemoryTurn turn, Set<String> coveredKeys) {
        List<Long> tagIds = turn.getTagIds();
        if (tagIds == null || tagIds.isEmpty()) {
            return false;  // 无标签 → 无 summary 能覆盖 → 拼原文（保守不丢内容）
        }
        String prefix = turn.getId() + ":";
        return tagIds.stream().allMatch(tag -> coveredKeys.contains(prefix + tag));
    }
}
