package com.superprogrammer.chat.service.internal;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.superprogrammer.chat.dto.MemoryProjectEntryVO;
import com.superprogrammer.chat.entity.MemoryProjectEntry;
import com.superprogrammer.chat.entity.MemoryProjectRule;
import com.superprogrammer.chat.mapper.MemoryProjectEntryMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 记忆二期 P1 · 收录审核 service（FR-005）。
 * <p>
 * 状态机：PENDING_REVIEW --收--> ACTIVE；PENDING_REVIEW --弃--> 软删 + 负例反哺
 * （条目 L1 滚进该项目规则 negative_examples，≤5 先进先出，复用 {@link MemoryProjectRuleService}）。
 * 作者可<b>申诉撤回</b>自己产生的条目（软删）；审核/撤回写审计日志。
 * <p>
 * 读权分层：owner/admin 看全量；普通成员仅看自己产生的条目（author_user_id=自己）。
 * 「为何被收录」= 命中规则文案 + 置信度（VO 带 ruleText）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryEntryReviewService {

    public static final String ACTION_APPROVE = "approve";
    public static final String ACTION_REJECT = "reject";

    private final MemoryProjectEntryMapper entryMapper;
    private final MemoryProjectRuleService ruleService;

    /** 条目列表：owner/admin 全量、成员仅自己产生的；附命中规则文案。 */
    public List<MemoryProjectEntryVO> listEntries(Long projectId, String status, Long viewerId) {
        boolean privileged = ruleService.isOwnerOrAdmin(projectId, viewerId);
        List<MemoryProjectEntryVO> list = entryMapper.listByProject(projectId, status,
                privileged ? null : viewerId);
        MemoryProjectRule rule = ruleService.findActiveRule(projectId);
        String ruleText = rule != null ? rule.getRuleText() : null;
        list.forEach(vo -> vo.setRuleText(ruleText));
        return list;
    }

    /**
     * 审核（owner/admin 权边界内建于本方法，防 controller 漏判）：approve → ACTIVE；reject → 软删 + 负例反哺。
     * 仅 PENDING_REVIEW 可审（重复审核/已 ACTIVE → 400）。
     */
    public void review(Long entryId, String action, Long operatorId) {
        MemoryProjectEntry entry = requirePendingEntry(entryId);
        if (!ruleService.isOwnerOrAdmin(entry.getProjectId(), operatorId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅项目 owner/admin 可审核收录条目");
        }
        if (ACTION_APPROVE.equals(action)) {
            MemoryProjectEntry update = new MemoryProjectEntry();
            update.setId(entry.getId());
            update.setStatus(MemoryProjectEntry.STATUS_ACTIVE);
            update.setReviewedBy(operatorId);
            update.setReviewedAt(OffsetDateTime.now());
            entryMapper.updateById(update);
            log.info("收录审核[收] entryId={} projectId={} operatorId={} confidence={}",
                    entry.getId(), entry.getProjectId(), operatorId, entry.getConfidence());
        } else if (ACTION_REJECT.equals(action)) {
            entryMapper.deleteById(entry.getId());   // @TableLogic 软删
            // 负例反哺：条目 L1（脱敏蒸馏文本，非原文）滚进规则负例，反哺路由精度
            ruleService.appendNegativeExample(entry.getProjectId(), entry.getL1Summary());
            log.info("收录审核[弃] entryId={} projectId={} operatorId={} → 软删+负例反哺",
                    entry.getId(), entry.getProjectId(), operatorId);
        } else {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "action 仅支持 approve/reject");
        }
    }

    /** 作者申诉撤回自己产生的条目（软删）。非作者 → 403。 */
    public void withdraw(Long entryId, Long userId) {
        MemoryProjectEntry entry = entryMapper.selectById(entryId);
        if (entry == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "条目不存在");
        }
        if (!entry.getAuthorUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅条目作者可撤回");
        }
        entryMapper.deleteById(entry.getId());
        log.info("收录条目作者撤回 entryId={} projectId={} authorId={}", entry.getId(), entry.getProjectId(), userId);
    }

    private MemoryProjectEntry requirePendingEntry(Long entryId) {
        MemoryProjectEntry entry = entryMapper.selectById(entryId);
        if (entry == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "条目不存在");
        }
        if (!MemoryProjectEntry.STATUS_PENDING_REVIEW.equals(entry.getStatus())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "仅待审核条目可审核");
        }
        return entry;
    }
}
