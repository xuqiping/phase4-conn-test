package com.superprogrammer.asset.controller;

import com.superprogrammer.asset.dto.MemberAddRequest;
import com.superprogrammer.asset.dto.MemberCandidateVO;
import com.superprogrammer.asset.dto.MemberRoleUpdateRequest;
import com.superprogrammer.asset.dto.MemberVO;
import com.superprogrammer.asset.dto.TransferRequest;
import com.superprogrammer.asset.service.AssetMemberService;
import com.superprogrammer.auth.security.RequirePermission;
import com.superprogrammer.common.result.R;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 项目资产库·成员授权 REST API（plan §S3 / FR-002，设计方案 §七）。
 *
 * <p>权限：第一层 {@code @RequirePermission("asset:write")}；第二层 service 层
 * {@code requireManage}（成员操作仅 owner，自移除除外）。
 *
 * <p>端点（前缀 /api/assets）：
 * <pre>
 * GET    /projects/{id}/members            成员列表（owner 行合成居首）
 * POST   /projects/{id}/members            邀请成员（owner）
 * PUT    /projects/{id}/members/{userId}   改角色（owner）
 * DELETE /projects/{id}/members/{userId}   移除成员（owner；自移除=退出）
 * POST   /projects/{id}/transfer           转让所有者（owner；旧 owner 降 editor）
 * </pre>
 */
@Slf4j
@RestController
@RequestMapping("/api/assets/projects/{id}/members")
@RequiredArgsConstructor
public class AssetMemberController {

    private final AssetMemberService memberService;

    @GetMapping
    @RequirePermission("asset:write")
    public ResponseEntity<R<List<MemberVO>>> list(@PathVariable("id") Long id) {
        return ResponseEntity.ok(R.ok(memberService.list(id, getCurrentUserId(), isAdmin())));
    }

    @GetMapping("/candidates")
    @RequirePermission("asset:write")
    public ResponseEntity<R<List<MemberCandidateVO>>> searchCandidates(
            @PathVariable("id") Long id,
            @RequestParam(defaultValue = "") String keyword) {
        return ResponseEntity.ok(R.ok(memberService.searchCandidates(
                id, getCurrentUserId(), isAdmin(), keyword)));
    }

    @PostMapping
    @RequirePermission("asset:write")
    public ResponseEntity<R<MemberVO>> invite(@PathVariable("id") Long id, @RequestBody MemberAddRequest req) {
        return ResponseEntity.ok(R.ok("成员已添加", memberService.invite(id, getCurrentUserId(), isAdmin(), req)));
    }

    @PutMapping("/{userId}")
    @RequirePermission("asset:write")
    public ResponseEntity<R<Void>> changeRole(@PathVariable("id") Long id,
                                              @PathVariable Long userId,
                                              @RequestBody MemberRoleUpdateRequest req) {
        memberService.changeRole(id, getCurrentUserId(), isAdmin(), userId, req.getRole());
        return ResponseEntity.ok(R.ok("角色已更新", null));
    }

    @DeleteMapping("/{userId}")
    @RequirePermission("asset:write")
    public ResponseEntity<R<Void>> remove(@PathVariable("id") Long id, @PathVariable Long userId) {
        memberService.remove(id, getCurrentUserId(), isAdmin(), userId);
        return ResponseEntity.ok(R.ok("成员已移除", null));
    }

    private Long getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? null : (Long) auth.getPrincipal();
    }

    private boolean isAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(a -> "ROLE_admin".equalsIgnoreCase(a) || "ROLE_ADMIN".equalsIgnoreCase(a));
    }
}
