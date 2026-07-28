package com.superprogrammer.chat.service.internal;

import com.superprogrammer.chat.entity.MemorySummaryCoverage;
import com.superprogrammer.chat.entity.MemoryTurn;
import com.superprogrammer.chat.mapper.MemorySummaryCoverageMapper;
import com.superprogrammer.chat.mapper.MemoryTurnMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 计划12 · D-5 · 召回 ⑥ 拼增量流水账（总体设计 §3.3 ⑥ + §6 向量 14 + 性能「防 N+1」）。
 * <p>
 * <b>allCovered 严格策略</b>（统一按 coverage(user_id=self) 判）：
 * <ul>
 *   <li>该 turn 贴的每个 tag_id 都有 coverage 行 → 跳原文（已被自己总结吃进，走 D-4 summary 即可）。</li>
 *   <li>某 tag 未覆盖 → 拼该 turn 原文（l1/l2，D-6 装配格式化）。</li>
 *   <li>turn 无 tag_id → 拼原文（保守不丢内容，无 tag 无 summary 能覆盖）。</li>
 * </ul>
 * 召回恒只认 {@code gen_done=true} 的 turn（mapper SQL 限定，raw 不参与）；他人 turn 同样按自己 coverage 判。
 * <p>
 * <b>防 N+1</b>（性能预案）：批量 {@code WHERE turn_id IN (...)} 一次取全部 coverage（{@link MemorySummaryCoverageMapper#findByUserAndTurns}）。
 * <p>
 * <b>I3 离职开关</b>（L10，§3.7 line 158）：{@code scope.includeDeparted=false} 时剔 readableAuthors ∩ DEPARTED
 * （<b>优先级高于人员多选</b>——即便 ACL 授权了离职 target 也不召回）；{@code true} 时保留（标注由 Pipeline 装配）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryTurnPatcher {

    private final MemoryTurnMapper turnMapper;
    private final MemorySummaryCoverageMapper coverageMapper;
    private final MemoryRecallAclResolver aclResolver;
    private final MemoryDepartedResolver departedResolver;

    /**
     * 收集 scope 内「未全覆盖、需拼原文」的流水账。
     *
     * @param scope  召回 scope
     * @param userId 召回者
     * @return 未覆盖 turns（D-6 装配拼 l1/l2）；空 scope 或无未覆盖 → 空表
     */
    public List<MemoryTurn> collectUncovered(RecallScope scope, Long userId) {
        if (scope.isEmpty()) {
            return List.of();
        }
        String direction = scope.direction().name();
        RecallTimeWindow tw = scope.timeWindow();

        List<MemoryTurn> acc = new ArrayList<>();
        if (scope.personalOn()) {
            List<MemoryTurn> personal = turnMapper.findPersonalRecallableTurns(
                    userId, direction, tw.start(), tw.end(), tw.relativeDays());
            acc.addAll(personal);
        }
        for (Long projectId : scope.projectIds()) {
            Set<Long> authors = aclResolver.readableAuthors(projectId, userId);
            if (authors.isEmpty()) {
                log.debug("patcher projectId={} reader={} 无可读作者（向量14），skip", projectId, userId);
                continue;
            }
            // I3 L10 离职开关（§3.7 line158）：关 → 剔 DEPARTED authors（优先级高于人员多选）
            if (!scope.includeDeparted()) {
                Set<Long> departed = departedResolver.resolveDeparted(projectId).intersectDeparted(authors);
                if (!departed.isEmpty()) {
                    authors = new HashSet<>(authors);
                    authors.removeAll(departed);
                    log.debug("patcher projectId={} reader={} includeDeparted=false 剔 DEPARTED {} 人 → 剩 {} 作者",
                            projectId, userId, departed.size(), authors.size());
                }
            }
            if (authors.isEmpty()) {
                continue;  // 剔完空（全 DEPARTED + 开关关）
            }
            List<MemoryTurn> projectTurns = turnMapper.findProjectRecallableTurns(
                    projectId, userId, List.copyOf(authors), direction, tw.start(), tw.end(), tw.relativeDays());
            acc.addAll(projectTurns);
        }

        List<MemoryTurn> turns = dedupById(acc);
        if (turns.isEmpty()) {
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

    /** 按 turn id 去重（个人 + 自己挂项目的同 turn 可能重复），保留顺序。 */
    private List<MemoryTurn> dedupById(List<MemoryTurn> acc) {
        return new ArrayList<>(acc.stream()
                .filter(t -> t != null && t.getId() != null)
                .collect(Collectors.toMap(MemoryTurn::getId, t -> t, (a, b) -> a, LinkedHashMap::new))
                .values());
    }
}
