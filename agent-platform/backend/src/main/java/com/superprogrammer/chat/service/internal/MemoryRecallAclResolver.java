package com.superprogrammer.chat.service.internal;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.superprogrammer.chat.entity.MemoryProjectMember;
import com.superprogrammer.chat.mapper.MemoryProjectMemberMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 项目记忆读取前置 resolver（记忆二期 P1 · 简化版，设计 §5「ACL 简化收益」）。
 * <p>
 * 二期定案：项目记忆=蒸馏条目（项目资产，成员即可读），reader×target 矩阵失去存在意义
 * （{@code memory_recall_acl} 表 V67 DROP，recall-acl 端点下线）。本 resolver 从一期
 * 「五路径 + ACL 授权集」简化为<b>单路径成员判定</b>：
 * <ul>
 *   <li>读者是项目 <b>ACTIVE 成员</b> → 可读项目全部成员的流水账（含 DEPARTED，保交接由 L10 开关过滤）；</li>
 *   <li>非成员 / DEPARTED → 空集（失读权，二期 §8.1）。</li>
 * </ul>
 * 安全语义反而更强：原文不可达（二期条目本就脱敏），recall_admin 概念随之作废。
 * <p>
 * <b>summary 不受本 resolver 影响</b>（恒只读自己）——本 resolver 只管流水账读取范围。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryRecallAclResolver {

    private static final String STATUS_ACTIVE = "ACTIVE";

    private final MemoryProjectMemberMapper memberMapper;

    /**
     * 项目内 reader 可读的作者 user_id 集（二期：ACTIVE 成员 → 全项目成员；否则空集）。
     *
     * @param projectId    项目 id（null → 空集，个人 scope 不走本 resolver）
     * @param readerUserId 读者 user id（null → 空集）
     * @return 可读作者集（含 DEPARTED，由调用方按 L10 再过滤）；空集 = 无权
     */
    public Set<Long> readableAuthors(Long projectId, Long readerUserId) {
        if (projectId == null || readerUserId == null) {
            return Collections.emptySet();
        }
        MemoryProjectMember reader = memberMapper.selectOne(
                new LambdaQueryWrapper<MemoryProjectMember>()
                        .eq(MemoryProjectMember::getProjectId, projectId)
                        .eq(MemoryProjectMember::getUserId, readerUserId));
        if (reader == null || !STATUS_ACTIVE.equals(reader.getStatus())) {
            log.debug("readableAuthors 非成员/DEPARTED projectId={} reader={} → 空（二期失读权）",
                    projectId, readerUserId);
            return Collections.emptySet();
        }
        // ACTIVE 成员 → 项目全部成员（含 DEPARTED，保交接由 L10 开关在召回层过滤）
        List<MemoryProjectMember> all = memberMapper.selectList(
                new LambdaQueryWrapper<MemoryProjectMember>()
                        .eq(MemoryProjectMember::getProjectId, projectId));
        Set<Long> authors = all.stream()
                .map(MemoryProjectMember::getUserId)
                .collect(Collectors.toCollection(HashSet::new));
        log.debug("readableAuthors ACTIVE 成员 projectId={} reader={} → 全部 {} 名成员（二期简化）",
                projectId, readerUserId, authors.size());
        return authors;
    }
}
