package com.superprogrammer.chat.service.internal;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.superprogrammer.chat.dto.MemoryProjectEntryVO;
import com.superprogrammer.chat.entity.MemoryProjectMember;
import com.superprogrammer.chat.mapper.MemoryProjectEntryMapper;
import com.superprogrammer.chat.mapper.MemoryProjectLinkMapper;
import com.superprogrammer.chat.mapper.MemoryProjectMemberMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 记忆二期 P1 · 项目条目召回合流（FR-007，pipeline ①.5 步）+ P2 授权链合流（FR-102）。
 * <p>
 * scope 内项目 → 过滤为「读者是其 ACTIVE 成员」的集（<b>读权咽喉</b>：DEPARTED 失读权、
 * 非成员不可读，设计 §5）→ 批量查 ACTIVE 条目（一次 IN 查询，禁 per-project 循环防 N+1）。
 * <p>
 * <b>P2 授权合流</b>：读者可读的 parent 项目若存在 {@code memory_project_links} ACTIVE 链，
 * 其 child 项目条目一并合流（<b>单级一跳，不递归传递</b>）；child 条目打
 * {@code viaAuthorizedLink=true} 瞬态标记，装配层据此加「来自授权项目·X」前缀。
 * 授权链查询失败 → 降级仅回成员项目条目（绝不动主干）。
 * <p>
 * 条目标签并入 ② 聚合由 pipeline 编排（本类只出数据）；装配打作者前缀在 ⑦。
 * P4 前条目无 coverage —— ⑥ 恒拼（标签命中筛选在 pipeline）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryEntryRecallService {

    private static final String STATUS_ACTIVE_MEMBER = "ACTIVE";

    private final MemoryProjectMemberMapper memberMapper;
    private final MemoryProjectEntryMapper entryMapper;
    private final MemoryProjectLinkMapper linkMapper;

    /**
     * 收集读者在 scope 项目内可读的 ACTIVE 条目（成员项目 ∪ ACTIVE 授权 child 项目）。
     *
     * @param scopeProjectIds 召回 scope 的项目集（未鉴权）
     * @param readerId        召回者
     * @return ACTIVE 条目（读者 ACTIVE 成员的项目 + 这些项目 ACTIVE 授权链的 child；其他静默排除）
     */
    public List<MemoryProjectEntryVO> collectActiveEntries(List<Long> scopeProjectIds, Long readerId) {
        if (scopeProjectIds == null || scopeProjectIds.isEmpty() || readerId == null) {
            return List.of();
        }
        // 读权咽喉：读者 ACTIVE 成员身份过滤（DEPARTED/非成员失读权）
        List<Long> readable = memberMapper.selectList(new LambdaQueryWrapper<MemoryProjectMember>()
                        .select(MemoryProjectMember::getProjectId)
                        .eq(MemoryProjectMember::getUserId, readerId)
                        .eq(MemoryProjectMember::getStatus, STATUS_ACTIVE_MEMBER)
                        .in(MemoryProjectMember::getProjectId, scopeProjectIds))
                .stream().map(MemoryProjectMember::getProjectId).distinct().toList();
        if (readable.isEmpty()) {
            return List.of();
        }
        // P2 授权合流（FR-102）：ACTIVE 链 child 并入查询集（单级一跳）；失败降级仅成员项目
        Set<Long> authorizedChildIds = Set.of();
        try {
            authorizedChildIds = new HashSet<>(linkMapper.findActiveChildIds(readable));
        } catch (Exception e) {
            log.warn("授权 child 查询失败（降级仅成员项目） readerId={}: {}", readerId, e.getMessage());
        }
        List<Long> queryIds = new ArrayList<>(readable);
        for (Long cid : authorizedChildIds) {
            if (!queryIds.contains(cid)) {
                queryIds.add(cid);
            }
        }
        List<MemoryProjectEntryVO> entries = entryMapper.listActiveForRecall(queryIds);
        if (!authorizedChildIds.isEmpty()) {
            Set<Long> childSet = authorizedChildIds;
            Set<Long> memberSet = new HashSet<>(readable);
            // 授权标 = 纯 child 合流（child 恰是本人成员项目时成员身份优先，不打授权标）
            entries.forEach(e -> e.setViaAuthorizedLink(
                    childSet.contains(e.getProjectId()) && !memberSet.contains(e.getProjectId())));
        }
        return entries;
    }
}
