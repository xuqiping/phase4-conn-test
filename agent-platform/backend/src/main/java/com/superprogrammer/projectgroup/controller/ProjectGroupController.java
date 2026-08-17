package com.superprogrammer.projectgroup.controller;

import com.superprogrammer.auth.security.RequirePermission;
import com.superprogrammer.common.audit.AuditLog;
import com.superprogrammer.common.result.R;
import com.superprogrammer.projectgroup.dto.ProjectGroupAllocateRequest;
import com.superprogrammer.projectgroup.dto.ProjectGroupCandidateVO;
import com.superprogrammer.projectgroup.dto.ProjectGroupCreateRequest;
import com.superprogrammer.projectgroup.dto.ProjectGroupDetailVO;
import com.superprogrammer.projectgroup.dto.ProjectGroupMemberAddRequest;
import com.superprogrammer.projectgroup.dto.ProjectGroupMineVO;
import com.superprogrammer.projectgroup.dto.ProjectGroupQuotaRequest;
import com.superprogrammer.projectgroup.dto.ProjectGroupUpdateRequest;
import com.superprogrammer.projectgroup.service.ProjectGroupService;
import com.superprogrammer.projectgroup.service.ProjectGroupWalletService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 项目组 REST API（计划5 Step3，7x#3/#4）。
 *
 * <p>权限双层：第一层 {@code @RequirePermission("project-group:manage")}（V134 gated 码）；
 * 第二层 service {@code requireOwner}（组长级，admin 放行）。资金操作全部 @AuditLog。
 *
 * <p>端点（前缀 /api/project-groups）：
 * <pre>
 * POST   /                                    建组（组长自动成员行 quota NULL + 组池 0）
 * GET    /mine                                我的组列表（我建的+我在的，选择器数据源）
 * GET    /{id}                                组详情（组长/admin，管理页）
 * GET    /{id}/members/candidates             候选用户搜索（排除组长/已有成员）
 * PUT    /{id}                                改名
 * DELETE /{id}                                删组（软删，组池须 0）
 * POST   /{id}/members                        加成员（可带限额）
 * DELETE /{id}/members/{uid}                  移除成员
 * PUT    /{id}/members/{uid}/quota            调限额（null=不限）
 * POST   /{id}/members/{uid}/reset-used       重置成员已用（ADMIN_ADJUST delta=0 留痕）
 * POST   /{id}/allocate                       划拨（个人→组池）
 * POST   /{id}/reclaim                        回收（组池→个人，在途上限校验）
 * </pre>
 */
@Slf4j
@RestController
@RequestMapping("/api/project-groups")
@RequiredArgsConstructor
public class ProjectGroupController {

    private final ProjectGroupService groupService;
    private final ProjectGroupWalletService walletService;

    @PostMapping
    @RequirePermission("project-group:manage")
    @AuditLog(module = "project-group", action = "group_create", targetType = "project_group")
    public ResponseEntity<R<Long>> create(@RequestBody ProjectGroupCreateRequest req) {
        return ResponseEntity.ok(R.ok("项目组已创建",
                groupService.createGroup(getCurrentUserId(), req.getName(), req.getDescription())));
    }

    @GetMapping("/mine")
    @RequirePermission("project-group:manage")
    public ResponseEntity<R<List<ProjectGroupMineVO>>> mine() {
        return ResponseEntity.ok(R.ok(groupService.listMine(getCurrentUserId())));
    }

    @GetMapping("/{id}")
    @RequirePermission("project-group:manage")
    public ResponseEntity<R<ProjectGroupDetailVO>> detail(@PathVariable("id") Long id) {
        return ResponseEntity.ok(R.ok(groupService.getDetail(id, getCurrentUserId(), isAdmin())));
    }

    @GetMapping("/{id}/members/candidates")
    @RequirePermission("project-group:manage")
    public ResponseEntity<R<List<ProjectGroupCandidateVO>>> candidates(
            @PathVariable("id") Long id,
            @RequestParam(defaultValue = "") String keyword) {
        return ResponseEntity.ok(R.ok(groupService.searchCandidates(id, getCurrentUserId(), isAdmin(), keyword)));
    }

    @PutMapping("/{id}")
    @RequirePermission("project-group:manage")
    @AuditLog(module = "project-group", action = "group_rename", targetType = "project_group")
    public ResponseEntity<R<Void>> rename(@PathVariable("id") Long id, @RequestBody ProjectGroupUpdateRequest req) {
        groupService.rename(id, getCurrentUserId(), isAdmin(), req.getName());
        return ResponseEntity.ok(R.ok("组名已更新", null));
    }

    @DeleteMapping("/{id}")
    @RequirePermission("project-group:manage")
    @AuditLog(module = "project-group", action = "group_delete", targetType = "project_group")
    public ResponseEntity<R<Void>> delete(@PathVariable("id") Long id) {
        groupService.deleteGroup(id, getCurrentUserId(), isAdmin());
        return ResponseEntity.ok(R.ok("项目组已删除", null));
    }

    @PostMapping("/{id}/members")
    @RequirePermission("project-group:manage")
    @AuditLog(module = "project-group", action = "member_add", targetType = "project_group_member")
    public ResponseEntity<R<Void>> addMember(@PathVariable("id") Long id,
                                             @RequestBody ProjectGroupMemberAddRequest req) {
        groupService.addMember(id, getCurrentUserId(), isAdmin(), req.getUserId(), req.getQuotaLimitPoints());
        return ResponseEntity.ok(R.ok("成员已添加", null));
    }

    @DeleteMapping("/{id}/members/{uid}")
    @RequirePermission("project-group:manage")
    @AuditLog(module = "project-group", action = "member_remove", targetType = "project_group_member")
    public ResponseEntity<R<Void>> removeMember(@PathVariable("id") Long id, @PathVariable("uid") Long uid) {
        groupService.removeMember(id, getCurrentUserId(), isAdmin(), uid);
        return ResponseEntity.ok(R.ok("成员已移除", null));
    }

    @PutMapping("/{id}/members/{uid}/quota")
    @RequirePermission("project-group:manage")
    @AuditLog(module = "project-group", action = "member_quota", targetType = "project_group_member")
    public ResponseEntity<R<Void>> updateQuota(@PathVariable("id") Long id, @PathVariable("uid") Long uid,
                                               @RequestBody ProjectGroupQuotaRequest req) {
        groupService.updateQuota(id, getCurrentUserId(), isAdmin(), uid, req.getQuotaLimitPoints());
        return ResponseEntity.ok(R.ok("成员限额已更新", null));
    }

    @PostMapping("/{id}/members/{uid}/reset-used")
    @RequirePermission("project-group:manage")
    @AuditLog(module = "project-group", action = "member_reset_used", targetType = "project_group_member")
    public ResponseEntity<R<Void>> resetUsed(@PathVariable("id") Long id, @PathVariable("uid") Long uid) {
        groupService.resetUsed(id, getCurrentUserId(), isAdmin(), uid);
        return ResponseEntity.ok(R.ok("成员已用已重置", null));
    }

    @PostMapping("/{id}/allocate")
    @RequirePermission("project-group:manage")
    @AuditLog(module = "project-group", action = "wallet_allocate", targetType = "project_group_wallet")
    public ResponseEntity<R<Map<String, Object>>> allocate(@PathVariable("id") Long id,
                                                           @RequestBody ProjectGroupAllocateRequest req) {
        walletService.allocate(id, getCurrentUserId(), isAdmin(), req.getPoints(), req.getRemark());
        return ResponseEntity.ok(R.ok("划拨成功", Map.of("groupId", id, "points", req.getPoints())));
    }

    @PostMapping("/{id}/reclaim")
    @RequirePermission("project-group:manage")
    @AuditLog(module = "project-group", action = "wallet_reclaim", targetType = "project_group_wallet")
    public ResponseEntity<R<Map<String, Object>>> reclaim(@PathVariable("id") Long id,
                                                          @RequestBody ProjectGroupAllocateRequest req) {
        walletService.reclaim(id, getCurrentUserId(), isAdmin(), req.getPoints(), req.getRemark());
        return ResponseEntity.ok(R.ok("回收成功", Map.of("groupId", id, "points", req.getPoints())));
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
