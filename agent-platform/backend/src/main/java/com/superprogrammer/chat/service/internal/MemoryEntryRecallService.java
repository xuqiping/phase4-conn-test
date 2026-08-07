package com.superprogrammer.chat.service.internal;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.superprogrammer.chat.dto.MemoryProjectEntryVO;
import com.superprogrammer.chat.entity.MemoryProjectMember;
import com.superprogrammer.chat.mapper.MemoryProjectEntryMapper;
import com.superprogrammer.chat.mapper.MemoryProjectMemberMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 记忆二期 P1 · 项目条目召回合流（FR-007，pipeline ①.5 步）。
 * <p>
 * scope 内项目 → 过滤为「读者是其 ACTIVE 成员」的集（<b>读权咽喉</b>：DEPARTED 失读权、
 * 非成员不可读，设计 §5）→ 批量查 ACTIVE 条目（一次 IN 查询，禁 per-project 循环防 N+1）。
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

    /**
     * 收集读者在 scope 项目内可读的 ACTIVE 条目。
     *
     * @param scopeProjectIds 召回 scope 的项目集（未鉴权）
     * @param readerId        召回者
     * @return ACTIVE 条目（读者 ACTIVE 成员的项目；其他项目静默排除）
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
        return entryMapper.listActiveForRecall(readable);
    }
}
