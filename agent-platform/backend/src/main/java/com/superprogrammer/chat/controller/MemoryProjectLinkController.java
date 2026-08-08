package com.superprogrammer.chat.controller;

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
    @PostMapping("/links/{linkId}/approve")
    public ResponseEntity<R<Void>> approve(@PathVariable Long linkId) {
        linkService.approve(linkId, requireLogin());
        return ResponseEntity.ok(R.ok("已通过", null));
    }

    /** 审批拒绝（parent owner/admin；30 天内同对不可重发）。 */
    @PostMapping("/links/{linkId}/reject")
    public ResponseEntity<R<Void>> reject(@PathVariable Long linkId) {
        linkService.reject(linkId, requireLogin());
        return ResponseEntity.ok(R.ok("已拒绝", null));
    }

    /** 撤销 ACTIVE / 取消 PENDING（child owner；ACTIVE 撤销 parent owner/admin 亦可）。 */
    @DeleteMapping("/links/{linkId}")
    public ResponseEntity<R<Void>> revoke(@PathVariable Long linkId) {
        linkService.revoke(linkId, requireLogin());
        return ResponseEntity.ok(R.ok("已撤销", null));
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
