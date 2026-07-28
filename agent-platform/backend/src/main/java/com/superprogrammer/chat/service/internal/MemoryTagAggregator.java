package com.superprogrammer.chat.service.internal;

import com.superprogrammer.chat.dto.RecallTagMeta;
import com.superprogrammer.chat.mapper.MemoryTagMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 计划12 · D-2 · 召回 ② 聚合标签（总体设计 §3.3 ② + §6 向量 3/14）。
 * <p>
 * 把 {@link RecallScope} 内的流水账 {@code tag_ids} + 总结 {@code tag_id} 聚合成去重标签清单
 * （供 D-3 LLM 选标签）：
 * <ol>
 *   <li><b>个人 scope</b>（{@code personalOn}）→ {@link MemoryTagMapper#findPersonalRecallTags}
 *       （本人 {@code born_personal=true} turns + 本人个人总结）。</li>
 *   <li><b>项目 scope</b>（每个 {@code projectId}）→ 先经 {@link MemoryRecallAclResolver#readableAuthors}
 *       算可读作者集；<b>空集 skip</b>（防越权，向量 14）；非空才查项目标签。</li>
 *   <li>合并后 <b>按 tag_id 去重</b>（个人 + 自己挂项目的 turns 可能同 tag_id 重复），保留顺序（usage 倒序）。</li>
 * </ol>
 * <p>
 * <b>防标签名泄敏</b>（向量 3）：scope 外的 turn/summary 关联不到的 tag 不进聚合——mapper SQL 的
 * {@code tag_id IN (scope 内子查询)} 天然排除 scope 外标签。
 * <p>
 * <b>I3 接口预留</b>：{@code scope.includeDeparted} 离职开关本迭代不过滤（{@code readableAuthors} 含 DEPARTED，
 * 由 I3 接入时按开关滤 + 标注「已离开人员」）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryTagAggregator {

    private final MemoryTagMapper tagMapper;
    private final MemoryRecallAclResolver aclResolver;

    /**
     * 聚合 scope 内标签清单。
     *
     * @param scope  召回 scope（个人 on/off + 项目集 + direction + timeWindow）
     * @param userId 召回者
     * @return 按 tag_id 去重的标签元清单（usage_count 倒序）；空召回 scope 返空表
     */
    public List<RecallTagMeta> aggregate(RecallScope scope, Long userId) {
        if (scope.isEmpty()) {
            return List.of();
        }
        String direction = scope.direction().name();
        RecallTimeWindow tw = scope.timeWindow();

        List<RecallTagMeta> acc = new ArrayList<>();
        if (scope.personalOn()) {
            List<RecallTagMeta> personal = tagMapper.findPersonalRecallTags(
                    userId, direction, tw.start(), tw.end(), tw.relativeDays());
            acc.addAll(personal);
            log.debug("aggregate personal userId={} → {} 标签", userId, personal.size());
        }
        for (Long projectId : scope.projectIds()) {
            Set<Long> authors = aclResolver.readableAuthors(projectId, userId);
            if (authors.isEmpty()) {
                log.debug("aggregate projectId={} reader={} 无可读作者（向量14），skip", projectId, userId);
                continue;
            }
            List<RecallTagMeta> projectTags = tagMapper.findProjectRecallTags(
                    projectId, userId, List.copyOf(authors), direction, tw.start(), tw.end(), tw.relativeDays());
            acc.addAll(projectTags);
            log.debug("aggregate projectId={} reader={} authors={} → {} 标签",
                    projectId, userId, authors.size(), projectTags.size());
        }

        // 按 tag_id 去重（个人 + 自己挂项目 turns 同 tag_id 重复；null id 防御跳过）
        List<RecallTagMeta> deduped = new ArrayList<>(acc.stream()
                .filter(m -> m.getId() != null)
                .collect(Collectors.toMap(
                        RecallTagMeta::getId, m -> m, (a, b) -> a, LinkedHashMap::new))
                .values());
        log.debug("aggregate 合计 userId={} 原始 {} → 去重 {}", userId, acc.size(), deduped.size());
        return deduped;
    }
}
