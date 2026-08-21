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
import com.superprogrammer.projectgroup.dto.ProjectGroupOverviewVO;
import com.superprogrammer.projectgroup.dto.ProjectGroupOutputVO;
import com.superprogrammer.projectgroup.service.ProjectGroupQueryService;
import com.superprogrammer.projectgroup.service.ProjectGroupService;
import com.superprogrammer.projectgroup.service.ProjectGroupWalletService;
import com.superprogrammer.common.result.PageResult;
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
 * GET    /{id}/overview                       组长总览（组详情+组池流水分页，Step7）
 * GET    /{id}/outputs                        组产出列表（组长全员/成员仅自己，Step7）
 * </pre>
 */
@Slf4j
@RestController
@RequestMapping("/api/project-groups")
@RequiredArgsConstructor
public class ProjectGroupController {

    private final ProjectGroupService groupService;
    private final ProjectGroupWalletService walletService;
    private final ProjectGroupQueryService queryService;
    private final com.superprogrammer.projectgroup.service.ProjectGroupVisibilityService visibilityService;
    private final com.superprogrammer.projectgroup.service.ProjectGroupInviteService inviteService;
    private final com.superprogrammer.projectgroup.service.ProjectGroupPoolService poolService;

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

    /**
     * 邀请成员（17x#3，V138）：原「直接加成员」改「邀请制」——创建 PENDING 邀请 + 通知被邀请人，
     * 被邀请人在「我的邀请」同意后才落成员行。quota 快照随邀请携带。
     */
    @PostMapping("/{id}/members")
    @RequirePermission("project-group:manage")
    @AuditLog(module = "project-group", action = "member_invite", targetType = "project_group_member")
    public ResponseEntity<R<Void>> addMember(@PathVariable("id") Long id,
                                             @RequestBody ProjectGroupMemberAddRequest req) {
        inviteService.invite(id, getCurrentUserId(), isAdmin(), req.getUserId(), req.getQuotaLimitPoints());
        return ResponseEntity.ok(R.ok("邀请已发送，待对方同意后入组", null));
    }

    // ==================== 17x#3：邀请同意 ====================

    /** 组邀请列表（组长/admin，全状态）。 */
    @GetMapping("/{id}/invites")
    @RequirePermission("project-group:manage")
    public ResponseEntity<R<List<com.superprogrammer.projectgroup.dto.ProjectGroupInviteVO>>> listInvites(
            @PathVariable("id") Long id) {
        return ResponseEntity.ok(R.ok(inviteService.listByGroup(id, getCurrentUserId(), isAdmin())));
    }

    /** 我的待处理邀请（被邀请人视角）。 */
    @GetMapping("/invites/mine")
    @RequirePermission("project-group:manage")
    public ResponseEntity<R<List<com.superprogrammer.projectgroup.dto.ProjectGroupInviteVO>>> myInvites() {
        return ResponseEntity.ok(R.ok(inviteService.listMinePending(getCurrentUserId())));
    }

    /** 接受邀请（被邀请人本人）：落成员行。 */
    @PostMapping("/invites/{inviteId}/accept")
    @RequirePermission("project-group:manage")
    @AuditLog(module = "project-group", action = "invite_accept", targetType = "project_group_member")
    public ResponseEntity<R<Void>> acceptInvite(@PathVariable("inviteId") Long inviteId) {
        inviteService.accept(inviteId, getCurrentUserId());
        return ResponseEntity.ok(R.ok("已加入项目组", null));
    }

    /** 拒绝邀请（被邀请人本人）。 */
    @PostMapping("/invites/{inviteId}/decline")
    @RequirePermission("project-group:manage")
    @AuditLog(module = "project-group", action = "invite_decline", targetType = "project_group_member")
    public ResponseEntity<R<Void>> declineInvite(@PathVariable("inviteId") Long inviteId) {
        inviteService.decline(inviteId, getCurrentUserId());
        return ResponseEntity.ok(R.ok("已拒绝邀请", null));
    }

    /** 取消邀请（组长/admin，PENDING→CANCELED）。 */
    @DeleteMapping("/invites/{inviteId}")
    @RequirePermission("project-group:manage")
    @AuditLog(module = "project-group", action = "invite_cancel", targetType = "project_group_member")
    public ResponseEntity<R<Void>> cancelInvite(@PathVariable("inviteId") Long inviteId) {
        inviteService.cancel(inviteId, getCurrentUserId(), isAdmin());
        return ResponseEntity.ok(R.ok("邀请已取消", null));
    }

    // ==================== 17x#2：产出可见性设置 ====================

    /** 更新成员产出可见性（组长/admin）：OWN/ALL + 按模块稀疏覆盖。 */
    @PutMapping("/{id}/visibility")
    @RequirePermission("project-group:manage")
    @AuditLog(module = "project-group", action = "visibility_update", targetType = "project_group")
    public ResponseEntity<R<Void>> updateVisibility(
            @PathVariable("id") Long id,
            @RequestBody com.superprogrammer.projectgroup.dto.ProjectGroupVisibilityUpdateRequest req) {
        visibilityService.updateVisibility(id, getCurrentUserId(), isAdmin(),
                req.getMemberOutputVisibility(), req.getModuleVisibilityOverrides());
        return ResponseEntity.ok(R.ok("可见性设置已更新", null));
    }

    // ==================== 17x#4：公共池招募 ====================

    /** 公共池列表（全平台登录用户）：招募中的组 + 我的身份/我的申请状态。 */
    @GetMapping("/pool")
    @RequirePermission("project-group:manage")
    public ResponseEntity<R<List<com.superprogrammer.projectgroup.dto.ProjectGroupPoolItemVO>>> pool() {
        return ResponseEntity.ok(R.ok(poolService.listPublic(getCurrentUserId())));
    }

    /** 推入公共池（组长/admin）。 */
    @PostMapping("/{id}/publish")
    @RequirePermission("project-group:manage")
    @AuditLog(module = "project-group", action = "pool_publish", targetType = "project_group")
    public ResponseEntity<R<Void>> publish(@PathVariable("id") Long id) {
        poolService.publish(id, getCurrentUserId(), isAdmin());
        return ResponseEntity.ok(R.ok("已推入公共池，全平台可申请加入", null));
    }

    /** 撤出公共池（组长/admin）：级联 PENDING 申请失效。 */
    @DeleteMapping("/{id}/publish")
    @RequirePermission("project-group:manage")
    @AuditLog(module = "project-group", action = "pool_unpublish", targetType = "project_group")
    public ResponseEntity<R<Void>> unpublish(@PathVariable("id") Long id) {
        poolService.unpublish(id, getCurrentUserId(), isAdmin());
        return ResponseEntity.ok(R.ok("已撤出公共池", null));
    }

    /** 申请加入（本人）：PENDING + 通知组长。 */
    @PostMapping("/{id}/join-requests")
    @RequirePermission("project-group:manage")
    @AuditLog(module = "project-group", action = "pool_apply", targetType = "project_group_join_request")
    public ResponseEntity<R<Void>> applyJoin(@PathVariable("id") Long id,
                                             @RequestBody(required = false) com.superprogrammer.projectgroup.dto.ProjectGroupJoinApplyRequest req) {
        poolService.apply(id, getCurrentUserId(), req != null ? req.getMessage() : null);
        return ResponseEntity.ok(R.ok("申请已提交，待组长审批", null));
    }

    /** 组的申请列表（组长/admin 审批视角）。 */
    @GetMapping("/{id}/join-requests")
    @RequirePermission("project-group:manage")
    public ResponseEntity<R<List<com.superprogrammer.projectgroup.dto.ProjectGroupJoinRequestVO>>> listJoinRequests(
            @PathVariable("id") Long id) {
        return ResponseEntity.ok(R.ok(poolService.listRequests(id, getCurrentUserId(), isAdmin())));
    }

    /** 我的申请（申请人视角，跨组）。 */
    @GetMapping("/join-requests/mine")
    @RequirePermission("project-group:manage")
    public ResponseEntity<R<List<com.superprogrammer.projectgroup.dto.ProjectGroupJoinRequestVO>>> myJoinRequests() {
        return ResponseEntity.ok(R.ok(poolService.listMine(getCurrentUserId())));
    }

    /** 审批（组长/admin）：approve=true→通过落成员行；false→拒绝。 */
    @PutMapping("/join-requests/{requestId}/decision")
    @RequirePermission("project-group:manage")
    @AuditLog(module = "project-group", action = "pool_decide", targetType = "project_group_join_request")
    public ResponseEntity<R<Void>> decideJoinRequest(
            @PathVariable("requestId") Long requestId,
            @RequestBody com.superprogrammer.projectgroup.dto.ProjectGroupJoinDecisionRequest req) {
        if (req.getApprove() == null) {
            throw new com.superprogrammer.common.exception.BusinessException(
                    com.superprogrammer.common.exception.ErrorCode.BAD_REQUEST, "approve 必填");
        }
        poolService.decide(requestId, getCurrentUserId(), isAdmin(), req.getApprove());
        return ResponseEntity.ok(R.ok(req.getApprove() ? "已通过，申请人已入组" : "已拒绝", null));
    }

    /** 取消我的待审批申请（申请人本人）。 */
    @DeleteMapping("/join-requests/{requestId}")
    @RequirePermission("project-group:manage")
    @AuditLog(module = "project-group", action = "pool_apply_cancel", targetType = "project_group_join_request")
    public ResponseEntity<R<Void>> cancelJoinRequest(@PathVariable("requestId") Long requestId) {
        poolService.cancelMine(requestId, getCurrentUserId());
        return ResponseEntity.ok(R.ok("申请已取消", null));
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

    /** 任免组内角色（17x#2，V139，仅组长/admin）：MEMBER↔MANAGER；OWNER 行 400。 */
    @PutMapping("/{id}/members/{uid}/role")
    @RequirePermission("project-group:manage")
    @AuditLog(module = "project-group", action = "member_role", targetType = "project_group_member")
    public ResponseEntity<R<Void>> updateMemberRole(@PathVariable("id") Long id, @PathVariable("uid") Long uid,
                                                    @RequestBody com.superprogrammer.projectgroup.dto.ProjectGroupRoleRequest req) {
        groupService.updateMemberRole(id, getCurrentUserId(), isAdmin(), uid, req.getRole());
        return ResponseEntity.ok(R.ok("成员角色已更新", null));
    }

    /** 设成员功能开关（17x#2，V139，组长/管理/admin，目标仅 MEMBER 行）：null=不限，[]=全禁。 */
    @PutMapping("/{id}/members/{uid}/kinds")
    @RequirePermission("project-group:manage")
    @AuditLog(module = "project-group", action = "member_kinds", targetType = "project_group_member")
    public ResponseEntity<R<Void>> updateMemberKinds(@PathVariable("id") Long id, @PathVariable("uid") Long uid,
                                                     @RequestBody com.superprogrammer.projectgroup.dto.ProjectGroupMemberKindsRequest req) {
        groupService.updateMemberKinds(id, getCurrentUserId(), isAdmin(), uid, req.getAllowedKinds());
        return ResponseEntity.ok(R.ok("成员可用模块已更新", null));
    }

    /** 设成员级可见性覆盖（17x#2，V139，组长/管理/admin，目标仅 MEMBER 行）：空 map=清空回落组级。 */
    @PutMapping("/{id}/members/{uid}/visibility-overrides")
    @RequirePermission("project-group:manage")
    @AuditLog(module = "project-group", action = "member_visibility", targetType = "project_group_member")
    public ResponseEntity<R<Void>> updateMemberVisibility(@PathVariable("id") Long id, @PathVariable("uid") Long uid,
                                                          @RequestBody com.superprogrammer.projectgroup.dto.ProjectGroupMemberVisibilityRequest req) {
        visibilityService.updateMemberVisibility(id, getCurrentUserId(), isAdmin(), uid, req.getOverrides());
        return ResponseEntity.ok(R.ok("成员可见性覆盖已更新", null));
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

    // ==================== Step7：推进查询（overview 组长总览 / outputs 产出列表） ====================

    /**
     * 组长总览（组长/admin）：组详情 + 组池流水倒序分页。
     * 普通成员 403（service requireOwner 口径，管理页组长专属）。
     */
    @GetMapping("/{id}/overview")
    @RequirePermission("project-group:manage")
    public ResponseEntity<R<ProjectGroupOverviewVO>> overview(
            @PathVariable("id") Long id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ResponseEntity.ok(R.ok(queryService.overview(id, getCurrentUserId(), isAdmin(), page, size)));
    }

    /**
     * 组产出列表：组长/admin 看全员（可按 memberUserId/kind/时间筛选）；
     * 普通成员仅看自己行（忽略筛选强制 self）；非成员 403。
     */
    @GetMapping("/{id}/outputs")
    @RequirePermission("project-group:manage")
    public ResponseEntity<R<PageResult<ProjectGroupOutputVO>>> outputs(
            @PathVariable("id") Long id,
            @RequestParam(required = false) Long memberUserId,
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ResponseEntity.ok(R.ok(queryService.outputs(
                id, getCurrentUserId(), isAdmin(), memberUserId, kind,
                parseTime(from, "from"), parseTime(to, "to"), page, size)));
    }

    /** ISO-8601 偏移时间解析（前端 toISOString 对齐）；空/非法 400。 */
    private static java.time.OffsetDateTime parseTime(String raw, String name) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return java.time.OffsetDateTime.parse(raw);
        } catch (java.time.format.DateTimeParseException e) {
            throw new com.superprogrammer.common.exception.BusinessException(
                    com.superprogrammer.common.exception.ErrorCode.BAD_REQUEST, name + " 时间格式非法");
        }
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
