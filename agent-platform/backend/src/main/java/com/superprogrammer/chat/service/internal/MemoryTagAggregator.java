package com.superprogrammer.chat.service.internal;

import com.superprogrammer.chat.dto.RecallTagMeta;
import com.superprogrammer.chat.mapper.MemoryTagMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 计划12 · D-2 · 召回 ② 聚合标签（总体设计 §3.3 ② + §6 向量 3/14）。
 * <p>
 * <b>二期 P1（V67，FR-006）</b>：turns 纯个人域——本类只聚合本人 turns.tag_ids ∪ 本人个人总结 tag_id；
 * 项目侧条目标签由 ①.5 条目合流后在 pipeline 并入候选（{@link MemoryRecallPipeline}）。
 * 一期项目 scope 聚合（readableAuthors ACL + findProjectRecallTags）随项目挂载列 DROP 整体下线。
 * <p>
 * <b>防标签名泄敏</b>（向量 3）：scope 外的 turn/summary 关联不到的 tag 不进聚合——mapper SQL 的
 * {@code tag_id IN (scope 内子查询)} 天然排除 scope 外标签。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryTagAggregator {

    private final MemoryTagMapper tagMapper;

    /**
     * 聚合 scope 内标签清单（二期 P1：仅本人个人域）。
     *
     * @param scope  召回 scope（个人 on/off + direction + timeWindow；项目集由条目合流承担）
     * @param userId 召回者
     * @return 标签元清单（usage_count 倒序）；空召回 scope / 个人关 → 空表
     */
    public List<RecallTagMeta> aggregate(RecallScope scope, Long userId) {
        if (scope.isEmpty() || !scope.personalOn()) {
            return List.of();
        }
        RecallTimeWindow tw = scope.timeWindow();
        List<RecallTagMeta> personal = tagMapper.findPersonalRecallTags(
                userId, scope.direction().name(), tw.start(), tw.end(), tw.relativeDays());
        log.debug("aggregate personal userId={} → {} 标签", userId, personal.size());
        return personal;
    }
}
