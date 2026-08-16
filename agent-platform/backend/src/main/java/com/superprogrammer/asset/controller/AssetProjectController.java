package com.superprogrammer.asset.controller;

import com.superprogrammer.asset.dto.ProjectCreateRequest;
import com.superprogrammer.asset.dto.ProjectUpdateRequest;
import com.superprogrammer.asset.dto.ProjectVO;
import com.superprogrammer.asset.dto.TransferRequest;
import com.superprogrammer.asset.service.AssetMemberService;
import com.superprogrammer.asset.service.AssetProjectService;
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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 项目资产库·项目 REST API（plan §S2 / FR-001，设计方案 §九 9.2）。
 *
 * <p>权限 gated：所有端点 {@code @RequirePermission("asset:write")}（第一层平台权限，切面 403 兜底）。
 * 项目数据权限（第二层）由 {@code AssetAclService} 在 service 层咽喉点判定。
 *
 * <p>端点（前缀 /api/assets）：
 * <pre>
 * POST   /projects          新建项目
 * GET    /projects          列表（owner+member，各带 role，前端分 Tab）
 * GET    /projects/{id}     详情
 * PUT    /projects/{id}     更新（含 narrative_roles 受控词汇维护，L10）
 * DELETE /projects/{id}     删除（级联软删，L4，owner only）
 * </pre>
 */
@Slf4j
@RestController
@RequestMapping("/api/assets/projects")
@RequiredArgsConstructor
public class AssetProjectController {

    private final AssetProjectService projectService;
    private final AssetMemberService memberService;

    @PostMapping
    @RequirePermission("asset:write")
    public ResponseEntity<R<ProjectVO>> create(@RequestBody ProjectCreateRequest req) {
        return ResponseEntity.ok(R.ok("项目已创建", projectService.create(getCurrentUserId(), req)));
    }

    @GetMapping
    @RequirePermission("asset:write")
    public ResponseEntity<R<List<ProjectVO>>> list() {
        return ResponseEntity.ok(R.ok(projectService.list(getCurrentUserId(), isAdmin())));
    }

    @GetMapping("/{id}")
    @RequirePermission("asset:write")
    public ResponseEntity<R<ProjectVO>> get(@PathVariable Long id) {
        return ResponseEntity.ok(R.ok(projectService.get(id, getCurrentUserId(), isAdmin())));
    }

    @PutMapping("/{id}")
    @RequirePermission("asset:write")
    public ResponseEntity<R<ProjectVO>> update(@PathVariable Long id, @RequestBody ProjectUpdateRequest req) {
        return ResponseEntity.ok(R.ok("项目已更新", projectService.update(id, getCurrentUserId(), isAdmin(), req)));
    }

    @DeleteMapping("/{id}")
    @RequirePermission("asset:write")
    public ResponseEntity<R<Void>> delete(@PathVariable Long id) {
        projectService.delete(id, getCurrentUserId(), isAdmin());
        return ResponseEntity.ok(R.ok("项目已删除", null));
    }

    /** 转让所有者（旧 owner 降 editor，仅 owner 可操作）。 */
    @PostMapping("/{id}/transfer")
    @RequirePermission("asset:write")
    public ResponseEntity<R<Void>> transfer(@PathVariable Long id, @RequestBody TransferRequest req) {
        memberService.transfer(id, getCurrentUserId(), isAdmin(), req);
        return ResponseEntity.ok(R.ok("项目已转让", null));
    }

    /** 项目设置（2x第三轮C6）：成员打分开关 + 内容模式 SHARED/PERSONAL。仅 OWNER。 */
    @PatchMapping("/{id}/settings")
    @RequirePermission("asset:write")
    @com.superprogrammer.common.audit.AuditLog(module = "asset", action = "project_settings", targetType = "asset_project")
    public ResponseEntity<R<ProjectVO>> updateSettings(@PathVariable Long id,
                                                       @RequestBody com.superprogrammer.asset.dto.ProjectSettingsRequest req) {
        return ResponseEntity.ok(R.ok("项目设置已更新",
                projectService.updateSettings(id, getCurrentUserId(), isAdmin(), req)));
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
