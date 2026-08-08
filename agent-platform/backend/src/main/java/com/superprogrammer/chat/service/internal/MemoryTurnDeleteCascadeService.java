package com.superprogrammer.chat.service.internal;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.superprogrammer.chat.entity.MemoryNotification;
import com.superprogrammer.chat.entity.MemoryProjectMember;
import com.superprogrammer.chat.entity.MemorySummary;
import com.superprogrammer.chat.mapper.MemoryEntryCoverageMapper;
import com.superprogrammer.chat.mapper.MemoryNotificationMapper;
import com.superprogrammer.chat.mapper.MemoryProjectEntryMapper;
import com.superprogrammer.chat.mapper.MemoryProjectMemberMapper;
import com.superprogrammer.chat.mapper.MemorySummaryCoverageMapper;
import com.superprogrammer.chat.mapper.MemorySummaryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 记忆二期 P4 · turn 删除级联服务（FR-304：废 12h 拒删，删 turn 随时生效 + 级联）。
 * <p>
 * 级联链（单事务）：
 * <pre>
 *   ① 引用被删 turn 的项目条目（memory_project_entries.source_turn_id）软删——V65 注释既定「P4 D6」；
 *   ② 波及总结双路收集：source_turn_ids @> [T]（个人/成员个人总结）∪ source_entry_ids @> [E]
 *     （被级联条目喂过的共享总结）→ 全部标 STALE + 清双侧 coverage（worker 下轮实时算链重生）；
 *   ③ 通知：个人总结 → 作者本人；项目共享总结 → 该项目 ACTIVE owner/admin。
 * </pre>
 * <b>废 12h</b>（FR-304）：被引用不再是拒删理由，统一 STALE + 重生，无 403。
 * <b>级联风暴防护</b>（坑点预判④）：级联只软删条目 + 标 STALE，重压异步排队给 worker，请求线程零 LLM。
 *
 * @see MemoryConsolidationWorker STALE 重生（turn 级 regenOne / 条目级 regenEntryOne）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryTurnDeleteCascadeService {

    private static final String NOTIFY_TYPE = "SUMMARY_AFFECTED_BY_RECALL";

    private final MemoryProjectEntryMapper entryMapper;
    private final MemorySummaryMapper summaryMapper;
    private final MemorySummaryCoverageMapper coverageMapper;
    private final MemoryEntryCoverageMapper entryCoverageMapper;
    private final MemoryNotificationMapper notificationMapper;
    private final MemoryProjectMemberMapper memberMapper;

    /**
     * turn 软删后的级联（调用方已完成 turn 本体软删 + ownership 校验）。
     *
     * @param operatorId 删除操作者（日志用）
     * @param turnIds    被软删的 turn id 集（单删=1 条；批量=分批传入）
     */
    @Transactional(rollbackFor = Exception.class)
    public void cascadeAfterTurnsDeleted(Long operatorId, List<Long> turnIds) {
        if (turnIds == null || turnIds.isEmpty()) {
            return;
        }
        // ① 级联软删项目条目
        List<Long> entryIds = entryMapper.findActiveIdsBySourceTurnIds(turnIds);
        if (!entryIds.isEmpty()) {
            int n = entryMapper.softDeleteBySourceTurnIds(turnIds);
            log.info("turn 删除级联软删项目条目 operatorId={} turns={} 条目={}", operatorId, turnIds.size(), n);
        }

        // ② 波及总结双路收集（去重）
        Map<Long, MemorySummary> affected = new LinkedHashMap<>();
        for (Long tid : turnIds) {
            List<MemorySummary> refs = summaryMapper.findSummariesReferencingTurn(tid);
            if (refs != null) {
                for (MemorySummary s : refs) {
                    affected.putIfAbsent(s.getId(), s);
                }
            }
        }
        for (Long eid : entryIds) {
            List<MemorySummary> refs = summaryMapper.findSummariesReferencingEntry(eid);
            if (refs != null) {
                for (MemorySummary s : refs) {
                    affected.putIfAbsent(s.getId(), s);
                }
            }
        }

        // ③ 标 STALE + 清双侧 coverage + 通知
        for (MemorySummary s : affected.values()) {
            if ("STALE".equals(s.getStatus())) {
                continue;  // 已 STALE 幂等跳过（同 summary 同版本只标一次，坑点预判③）
            }
            summaryMapper.markStatus(s.getId(), "STALE");
            coverageMapper.deleteBySummaryId(s.getId());
            entryCoverageMapper.deleteBySummaryId(s.getId());
            notifyAffected(s);
        }
        if (!affected.isEmpty()) {
            log.info("turn 删除级联标 STALE operatorId={} turns={} 波及总结={}", operatorId, turnIds.size(), affected.size());
        }
    }

    /** 波及通知：个人总结→作者本人；项目共享总结→项目 ACTIVE owner/admin。 */
    private void notifyAffected(MemorySummary s) {
        if ("PROJECT".equals(s.getScopeOwner())) {
            List<MemoryProjectMember> managers = memberMapper.selectList(
                    new LambdaQueryWrapper<MemoryProjectMember>()
                            .eq(MemoryProjectMember::getProjectId, s.getProjectId())
                            .eq(MemoryProjectMember::getStatus, "ACTIVE")
                            .in(MemoryProjectMember::getRole, "OWNER", "ADMIN"));
            for (MemoryProjectMember m : managers) {
                insertNotification(m.getUserId(), s.getId(),
                        "项目共享总结引用的流水账/条目已被作者删除，总结已标记待重生");
            }
        } else if (s.getUserId() != null) {
            insertNotification(s.getUserId(), s.getId(), "您的一条总结引用了被删除的流水账，已标记待重生");
        }
    }

    private void insertNotification(Long userId, Long refId, String message) {
        MemoryNotification n = new MemoryNotification();
        n.setUserId(userId);
        n.setType(NOTIFY_TYPE);
        n.setRefId(refId);
        n.setMessage(message);
        n.setCreatedAt(OffsetDateTime.now());
        notificationMapper.insert(n);
    }
}
