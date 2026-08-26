package com.superprogrammer.project.controller;

import com.superprogrammer.common.audit.AuditLog;
import com.superprogrammer.common.result.R;
import com.superprogrammer.project.dto.ProjectCreateRequest;
import com.superprogrammer.project.dto.ProjectMemberVO;
import com.superprogrammer.project.dto.ProjectShareRequest;
import com.superprogrammer.project.dto.ProjectVO;
import com.superprogrammer.project.service.ProjectService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 项目（记忆 scope 容器）CRUD + 共享，V33。/api/projects。
 * 鉴权在 ProjectService 内（owner/member/admin），不走 @RequirePermission。
 */
@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;

    @GetMapping
    public ResponseEntity<R<List<ProjectVO>>> list() {
        return ResponseEntity.ok(R.ok(projectService.listForUser(getOperatorId())));
    }

    @GetMapping("/{id}")
    public ResponseEntity<R<ProjectVO>> get(@PathVariable Long id) {
        return ResponseEntity.ok(R.ok(projectService.get(id, getOperatorId(), isAdmin())));
    }

    @PostMapping
    @AuditLog(module = "project", action = "project_create", targetType = "project")
    public ResponseEntity<R<ProjectVO>> create(@RequestBody ProjectCreateRequest body) {
        return ResponseEntity.ok(R.ok("项目创建成功", projectService.create(body, getOperatorId())));
    }

    @PutMapping("/{id}")
    @AuditLog(module = "project", action = "project_update", targetType = "project")
    public ResponseEntity<R<ProjectVO>> update(@PathVariable Long id, @RequestBody ProjectCreateRequest body) {
        return ResponseEntity.ok(R.ok(projectService.update(id, body, getOperatorId(), isAdmin())));
    }

    @DeleteMapping("/{id}")
    @AuditLog(module = "project", action = "project_delete", targetType = "project")
    public ResponseEntity<R<Void>> delete(@PathVariable Long id) {
        projectService.delete(id, getOperatorId(), isAdmin());
        return ResponseEntity.ok(R.ok("项目删除成功", null));
    }

    @GetMapping("/{id}/members")
    public ResponseEntity<R<List<ProjectMemberVO>>> listMembers(@PathVariable Long id) {
        return ResponseEntity.ok(R.ok(projectService.listMembers(id, getOperatorId(), isAdmin())));
    }

    @PostMapping("/{id}/members")
    @AuditLog(module = "project", action = "project_member_add", targetType = "project")
    public ResponseEntity<R<ProjectMemberVO>> addMember(@PathVariable Long id,
                                                        @RequestBody ProjectShareRequest body) {
        return ResponseEntity.ok(R.ok("共享成功", projectService.addMember(id, body, getOperatorId(), isAdmin())));
    }

    @DeleteMapping("/{id}/members/{memberId}")
    @AuditLog(module = "project", action = "project_member_remove", targetType = "project")
    public ResponseEntity<R<Void>> removeMember(@PathVariable Long id, @PathVariable Long memberId) {
        projectService.removeMember(id, memberId, getOperatorId(), isAdmin());
        return ResponseEntity.ok(R.ok("已移除成员", null));
    }

    private Long getOperatorId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return (Long) auth.getPrincipal();
    }

    private boolean isAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_admin".equalsIgnoreCase(a.getAuthority())
                        || "ROLE_ADMIN".equalsIgnoreCase(a.getAuthority()));
    }
}
