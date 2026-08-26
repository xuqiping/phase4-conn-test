package com.superprogrammer.chat.controller;

import com.superprogrammer.chat.dto.MemoryProjectRuleRequest;
import com.superprogrammer.chat.dto.MemoryProjectRuleVO;
import com.superprogrammer.chat.service.internal.MemoryProjectRuleService;
import com.superprogrammer.chat.service.internal.MemoryRosterService;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.common.audit.AuditLog;
import com.superprogrammer.common.result.R;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 记忆二期 P1 · 项目收录规则端点（FR-001）。
 * <p>
 * 命名空间沿用 {@code /api/chat/memory/projects/{pid}}（同 MemoryRosterController）。
 * <b>权边界</b>：
 * <ul>
 *   <li>{@code GET /rule}：项目 ACTIVE 成员可读（rule_text/正例/enabled 透明化）；negative_examples 仅 owner/admin。</li>
 *   <li>{@code PUT /rule}：仅 owner/admin（upsert，v1 每项目一条）。</li>
 * </ul>
 * 审计：保存走 created_by/updated_by + log.info。
 */
@Slf4j
@RestController
@RequestMapping("/api/chat/memory/projects/{projectId}")
@RequiredArgsConstructor
public class MemoryProjectRuleController {

    private final MemoryProjectRuleService ruleService;
    private final MemoryRosterService rosterService;

    /** 读取收录规则（成员可见；无规则返 data=null；负例仅 owner/admin）。 */
    @GetMapping("/rule")
    public ResponseEntity<R<MemoryProjectRuleVO>> getRule(@PathVariable Long projectId) {
        Long uid = requireMember(projectId);
        return ResponseEntity.ok(R.ok(ruleService.getRule(projectId, uid)));
    }

    /** 保存收录规则（仅 owner/admin；upsert；anchor 同步重算，embed 失败→enabled 强制 false）。 */
    @PutMapping("/rule")
    @AuditLog(module = "memory", action = "project_rule_set", targetType = "memory_rule")
    public ResponseEntity<R<MemoryProjectRuleVO>> putRule(@PathVariable Long projectId,
                                                          @RequestBody MemoryProjectRuleRequest req) {
        Long operatorId = requireOwnerOrAdmin(projectId);
        MemoryProjectRuleVO vo = ruleService.saveRule(projectId, req, operatorId);
        log.info("收录规则保存 operatorId={} projectId={} ruleId={}", operatorId, projectId, vo.getId());
        return ResponseEntity.ok(R.ok("已保存", vo));
    }

    // ---- 权边界 helpers（承 MemoryRosterController 范式）----

    private Long requireMember(Long projectId) {
        Long uid = requireLogin();
        if (!rosterService.isMember(projectId, uid)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "非项目成员，无权查看收录规则");
        }
        return uid;
    }

    private Long requireOwnerOrAdmin(Long projectId) {
        Long uid = requireLogin();
        if (!ruleService.isOwnerOrAdmin(projectId, uid)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅项目 owner/admin 可配置收录规则");
        }
        return uid;
    }

    private Long requireLogin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Long uid = (auth == null || auth.getPrincipal() == null) ? null : (Long) auth.getPrincipal();
        if (uid == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "未登录");
        }
        return uid;
    }
}
