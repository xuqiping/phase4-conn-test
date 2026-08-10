package com.superprogrammer.chat.controller;

import com.superprogrammer.common.audit.AuditLog;
import com.superprogrammer.chat.dto.MemoryProjectLinkVO;
import com.superprogrammer.chat.service.internal.MemoryProjectLinkService;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.common.result.R;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 记忆二期 P2 · 项目授权端点（FR-101/103）。
 * <p>
 * <b>权边界</b>（细粒度判定内建 {@link MemoryProjectLinkService}，承 P1 决策 3）：
 * <ul>
 *   <li>{@code POST /projects/{pid}/links}：pid=授权方（child），body={"parentProjectId":n}；仅 child owner。</li>
 *   <li>{@code GET /links/mine}：我相关的授权链（任一侧 ACTIVE owner/admin）。</li>
 *   <li>{@code POST /links/{id}/approve|reject}：parent owner/admin。</li>
 *   <li>{@code DELETE /links/{id}}：child owner（PENDING 取消 / ACTIVE 撤销）或 parent owner/admin（ACTIVE 撤销）。</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/chat/memory")
@RequiredArgsConstructor
public class MemoryProjectLinkController {

    private final MemoryProjectLinkService linkService;

    /** 发起授权（child=path 项目；body.parentProjectId=被授权方）。 */
    @AuditLog(module = "memory", action = "link_request", targetType = "memory_project_link")
    @PostMapping("/projects/{projectId}/links")
    public ResponseEntity<R<MemoryProjectLinkVO>> request(@PathVariable Long projectId,
                                                          @RequestBody Map<String, Long> body) {
        Long parentProjectId = body != null ? body.get("parentProjectId") : null;
        MemoryProjectLinkVO vo = linkService.request(projectId, parentProjectId, requireLogin());
        return ResponseEntity.ok(R.ok("已发起，待对方项目 owner/admin 审批", vo));
    }

    /** 我相关的授权链（前端「我授权出去的 / 待我审批的」两栏数据源）。 */
    @GetMapping("/links/mine")
    public ResponseEntity<R<List<MemoryProjectLinkVO>>> listMine() {
        return ResponseEntity.ok(R.ok(linkService.listMine(requireLogin())));
    }

    /** 审批通过（parent owner/admin）。 */
    @AuditLog(module = "memory", action = "link_approve", targetType = "memory_project_link")
    @PostMapping("/links/{linkId}/approve")
    public ResponseEntity<R<Void>> approve(@PathVariable Long linkId) {
        linkService.approve(linkId, requireLogin());
        return ResponseEntity.ok(R.ok("已通过", null));
    }

    /** 审批拒绝（parent owner/admin；30 天内同对不可重发）。 */
    @AuditLog(module = "memory", action = "link_reject", targetType = "memory_project_link")
    @PostMapping("/links/{linkId}/reject")
    public ResponseEntity<R<Void>> reject(@PathVariable Long linkId) {
        linkService.reject(linkId, requireLogin());
        return ResponseEntity.ok(R.ok("已拒绝", null));
    }

    /** 撤销 ACTIVE / 取消 PENDING（三期非对称：child owner ACTIVE→挂起待审批；parent manager ACTIVE→即时撤销；PENDING child→软删）。 */
    @AuditLog(module = "memory", action = "link_revoke", targetType = "memory_project_link")
    @DeleteMapping("/links/{linkId}")
    public ResponseEntity<R<Void>> revoke(@PathVariable Long linkId) {
        linkService.revoke(linkId, requireLogin());
        return ResponseEntity.ok(R.ok("已撤销", null));
    }

    /** 三期：parent owner/admin 通过 child 的撤销申请（ACTIVE→REVOKED）。 */
    @AuditLog(module = "memory", action = "link_approve_revoke", targetType = "memory_project_link")
    @PostMapping("/links/{linkId}/approve-revoke")
    public ResponseEntity<R<Void>> approveRevoke(@PathVariable Long linkId) {
        linkService.approveRevoke(linkId, requireLogin());
        return ResponseEntity.ok(R.ok("已通过撤销，记忆授权已解除", null));
    }

    /** 三期：parent owner/admin 拒绝 child 的撤销申请（status 留 ACTIVE）。 */
    @AuditLog(module = "memory", action = "link_reject_revoke", targetType = "memory_project_link")
    @PostMapping("/links/{linkId}/reject-revoke")
    public ResponseEntity<R<Void>> rejectRevoke(@PathVariable Long linkId) {
        linkService.rejectRevoke(linkId, requireLogin());
        return ResponseEntity.ok(R.ok("已拒绝撤销，授权保持生效", null));
    }

    /** 三期：child owner 撤回自己挂起的撤销申请（status 留 ACTIVE）。 */
    @AuditLog(module = "memory", action = "link_withdraw_revoke", targetType = "memory_project_link")
    @PostMapping("/links/{linkId}/withdraw-revoke")
    public ResponseEntity<R<Void>> withdrawRevoke(@PathVariable Long linkId) {
        linkService.withdrawRevokeRequest(linkId, requireLogin());
        return ResponseEntity.ok(R.ok("已撤回撤销申请", null));
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
