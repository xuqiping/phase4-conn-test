package com.superprogrammer.chat.service.internal;

import com.superprogrammer.chat.entity.MemoryConflict;
import com.superprogrammer.chat.entity.MemoryNotification;
import com.superprogrammer.chat.entity.MemorySummary;
import com.superprogrammer.chat.mapper.MemoryConflictMapper;
import com.superprogrammer.chat.mapper.MemoryNotificationMapper;
import com.superprogrammer.chat.mapper.MemorySummaryCoverageMapper;
import com.superprogrammer.chat.mapper.MemorySummaryMapper;
import com.superprogrammer.chat.mapper.MemoryTurnMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 计划12 · E-4 · 总结时序冲突裁决服务（总体设计 §3.5 四选项 + §3.8 DISCARD 级联）。
 * <p>
 * <b>偏离 plan</b>：plan 列「改 {@code MemoryConflictService}」——后者是 legacy {@code user_memories}
 * 单值冲突栈（KEEP_CUSTOM/合并/re-embed）。新模型冲突只来自总结时序互斥（tag+summary，无 customValue）。
 * 故新建本类独立裁决（承 C/D/E-3 隔离裁决）。
 * <p>
 * <b>四选项</b>（设计 §3.5 line 141）：
 * <ul>
 *   <li>{@code KEEP_BOTH} —— 两方 PENDING summary 都回 CLEAN（按 summarized_at 自动排序）；</li>
 *   <li>{@code KEEP_NEW} / {@code KEEP_OLD} —— 留一方，败方 summary 软删 + 清 coverage，<b>turns 不动</b>；</li>
 *   <li>{@code DISCARD} —— 软删冲突 summary（conflict.summary_id）+ 其 source_turn_ids 全部 turns，
 *       走 §3.8 级联：12h 拒 + 他人引用 STALE + 删 coverage + 波及通知 + worker 重压缩。</li>
 * </ul>
 * <p>
 * <b>非作者不可裁决</b>（向量 6 + 15）：conflict.user_id 须 == 登录 uid，否则 NOT_FOUND（不区分存在性探测）。
 *
 * @see MemoryConsolidationTxService#createV47Conflict 建冲突（summary_id=触发方新 summary）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryConflictResolutionService {

    /** §3.8 12h 规则阈值。二期 P4（FR-304）已废——被引用不再拒删，统一 STALE+重生。 */
    @Deprecated
    private static final Duration HOURS_12 = Duration.ofHours(12);

    private static final List<String> VALID_DECISIONS = List.of("KEEP_BOTH", "KEEP_NEW", "KEEP_OLD", "DISCARD");

    private final MemoryConflictMapper conflictMapper;
    private final MemorySummaryMapper summaryMapper;
    private final MemorySummaryCoverageMapper coverageMapper;
    private final MemoryTurnMapper turnMapper;
    private final MemoryNotificationMapper notificationMapper;
    private final MemoryQueryCache queryCache;
    private final MemoryProjectLinkService linkService;

    /**
     * 执行裁决。
     *
     * @return true=裁决成功；false=冲突非 PENDING（已裁决过，幂等）
     * @throws BusinessException NOT_FOUND（不存在/无权）/ BAD_REQUEST（非法 decision）/ FORBIDDEN（DISCARD 12h 拒）
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean resolve(Long userId, Long conflictId, String decision) {
        MemoryConflict c = conflictMapper.selectById(conflictId);
        if (c == null || c.getTagId() == null) {
            // V47 冲突须 tag_id 非空；不存在统一 NOT_FOUND（防存在性探测）
            throw new BusinessException(ErrorCode.NOT_FOUND, "冲突不存在或无权操作");
        }
        if (!"PENDING".equals(c.getStatus())) {
            log.info("冲突已裁决过 conflictId={} status={} → 幂等返 false", conflictId, c.getStatus());
            return false;
        }
        if (!VALID_DECISIONS.contains(decision)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "非法裁决选项: " + decision);
        }

        MemorySummary trigger = summaryMapper.selectById(c.getSummaryId());
        if (trigger == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "冲突关联总结不存在");
        }
        // 二期 P4（FR-303「冲突裁决权随总结所有权」）：项目共享总结=项目 ACTIVE owner/admin 裁决
        // （冲突行 user_id 仅是触发者留痕）；个人总结=作者本人（一期语义不变）。
        boolean projectShared = "PROJECT".equals(trigger.getScopeOwner());
        if (projectShared) {
            if (!linkService.isOwnerOrAdmin(trigger.getProjectId(), userId)) {
                log.info("项目总结冲突裁决越权拦截 userId={} conflictId={} projectId={}",
                        userId, conflictId, trigger.getProjectId());
                throw new BusinessException(ErrorCode.NOT_FOUND, "冲突不存在或无权操作");
            }
        } else if (!c.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "冲突不存在或无权操作");
        }
        Long scopeProjectId = trigger.getProjectId();

        switch (decision) {
            case "KEEP_BOTH" -> keepBoth(userId, c, scopeProjectId, projectShared);
            case "KEEP_NEW" -> keepOne(userId, c, trigger, scopeProjectId, true, projectShared);
            case "KEEP_OLD" -> keepOne(userId, c, trigger, scopeProjectId, false, projectShared);
            case "DISCARD" -> discard(userId, c, trigger, projectShared);
        }

        conflictMapper.markV47Resolved(conflictId, decision);
        queryCache.evictUser(userId);
        log.info("冲突裁决 userId={} conflictId={} decision={} projectShared={}", userId, conflictId, decision, projectShared);
        return true;
    }

    // ---- KEEP_BOTH：两方 PENDING 都回 CLEAN（按 summarized_at 自动排序，无需用户填日期）----

    private void keepBoth(Long userId, MemoryConflict c, Long scopeProjectId, boolean projectShared) {
        List<MemorySummary> pendings = findPendings(userId, c.getTagId(), scopeProjectId, projectShared);
        for (MemorySummary s : pendings) {
            summaryMapper.markStatus(s.getId(), "CLEAN");
        }
    }

    /** PENDING_CONFLICT 双方取数：项目共享按 (project,tag,PROJECT) 域；个人按 (user,tag,scope)。 */
    private List<MemorySummary> findPendings(Long userId, Long tagId, Long scopeProjectId, boolean projectShared) {
        return projectShared
                ? summaryMapper.findByProjectTagScopeStatus(scopeProjectId, tagId, "PENDING_CONFLICT")
                : summaryMapper.findByUserTagScopeStatus(userId, tagId, scopeProjectId, "PENDING_CONFLICT");
    }

    // ---- KEEP_NEW / KEEP_OLD：留一方，败方软删 + 清 coverage，turns 不动 ----

    private void keepOne(Long userId, MemoryConflict c, MemorySummary trigger,
                         Long scopeProjectId, boolean keepNew, boolean projectShared) {
        List<MemorySummary> pendings = findPendings(userId, c.getTagId(), scopeProjectId, projectShared);
        Long survivor = keepNew ? trigger.getId() : firstOther(pendings, trigger.getId());
        if (survivor == null) {
            survivor = trigger.getId();  // 兜底（理论 pendings 含 trigger）
        }
        // 败方 = pendings 中除 survivor 外
        List<Long> losers = new ArrayList<>();
        for (MemorySummary s : pendings) {
            if (!s.getId().equals(survivor)) {
                losers.add(s.getId());
            }
        }
        if (!losers.isEmpty()) {
            summaryMapper.softDeleteByIds(losers);
            for (Long lid : losers) {
                coverageMapper.deleteBySummaryId(lid);
            }
        }
        summaryMapper.markStatus(survivor, "CLEAN");
    }

    private static Long firstOther(List<MemorySummary> pendings, Long excludeId) {
        for (MemorySummary s : pendings) {
            if (!s.getId().equals(excludeId)) {
                return s.getId();
            }
        }
        return null;
    }

    // ---- DISCARD：软删冲突 summary + source turns + §3.8 级联（12h 拒 / 他人 STALE / 通知）----

    private void discard(Long userId, MemoryConflict c, MemorySummary trigger, boolean projectShared) {
        if (projectShared) {
            // 二期 P4 项目共享总结 DISCARD：仅软删 summary 本体。条目是项目资产不动；
            // entry_coverage 保留（summary_id 指向软删行无害）——删了会让下轮总结把同批条目
            // 重压一遍重建同样冲突（死循环），故覆盖行随 summary 软删留档（worker 重生不触 PENDING 行）。
            summaryMapper.softDeleteByIds(List.of(trigger.getId()));
            log.info("项目共享总结 DISCARD userId={} summaryId={} projectId={}（条目保留+覆盖留档防重压循环）",
                    userId, trigger.getId(), trigger.getProjectId());
            return;
        }
        List<Long> sourceTurnIds = trigger.getSourceTurnIds() == null
                ? List.of() : trigger.getSourceTurnIds();

        // ① 二期 P4（FR-304）：废 12h 拒删——被他人引用不再是拒绝理由（无 403），
        //    他人引用方统一走 ④ STALE + 通知 + worker 重生。

        // ② 软删冲突 summary + 其 coverage
        summaryMapper.softDeleteByIds(List.of(trigger.getId()));
        coverageMapper.deleteBySummaryId(trigger.getId());

        // ③ 连带软删 source turns + 清作者侧 coverage（turns 软删后不进总结取数 → 防死循环）
        if (!sourceTurnIds.isEmpty()) {
            turnMapper.softDeleteByIds(sourceTurnIds);
            coverageMapper.deleteByTurnIdsAndUser(sourceTurnIds, userId);
        }

        // ④ 他人引用 summary → STALE + 清 coverage + 波及通知（worker 重压缩，不再有时长门槛）
        List<MemorySummary> otherRefs = collectOtherReferences(userId, sourceTurnIds);
        for (MemorySummary other : otherRefs) {
            summaryMapper.markStatus(other.getId(), "STALE");
            coverageMapper.deleteBySummaryId(other.getId());
            insertRecallNotification(other);
        }
    }

    /** 收集 source_turn_ids 被他人引用的全部 summary（去重）。 */
    private List<MemorySummary> collectOtherReferences(Long selfUserId, List<Long> turnIds) {
        List<MemorySummary> out = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (Long tid : turnIds) {
            List<MemorySummary> refs = summaryMapper.findSummariesReferencingTurn(tid);
            if (refs == null) continue;
            for (MemorySummary s : refs) {
                if (!s.getUserId().equals(selfUserId) && seen.add(s.getId())) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    /** 写 SUMMARY_AFFECTED_BY_RECALL 波及通知给他人 summary 作者（设计 §3.8 line 173）。 */
    private void insertRecallNotification(MemorySummary affected) {
        MemoryNotification n = new MemoryNotification();
        n.setUserId(affected.getUserId());
        n.setType("SUMMARY_AFFECTED_BY_RECALL");
        n.setRefId(affected.getId());
        n.setMessage("您的一条总结引用了被撤回的流水账，已标记待重生");
        n.setCreatedAt(OffsetDateTime.now());
        notificationMapper.insert(n);
    }

    /** 用户待裁决 V47 冲突列表（面板用）——本人冲突 ∪ 我任 owner/admin 项目的共享总结冲突
     *  （二期 P4 · FR-303 裁决权随总结所有权；共享行打 projectShared 瞬态标供前端 badge）。 */
    public List<MemoryConflict> listPending(Long userId) {
        List<MemoryConflict> mine = conflictMapper.findV47PendingByUser(userId);
        List<MemoryConflict> shared = conflictMapper.findV47PendingProjectSharedByManager(userId);
        if (shared == null || shared.isEmpty()) {
            return mine == null ? List.of() : mine;
        }
        Set<Long> mineIds = new HashSet<>();
        List<MemoryConflict> out = new ArrayList<>();
        if (mine != null) {
            for (MemoryConflict c : mine) {
                mineIds.add(c.getId());
                out.add(c);
            }
        }
        for (MemoryConflict c : shared) {
            if (mineIds.add(c.getId())) {  // 触发者本人已是 owner/admin → 去重
                c.setProjectShared(true);
                out.add(c);
            }
        }
        return out;
    }

    /** 用户待裁决冲突计数（badge 轮询）。 */
    public int countPending(Long userId) {
        return listPending(userId).size();
    }
}
