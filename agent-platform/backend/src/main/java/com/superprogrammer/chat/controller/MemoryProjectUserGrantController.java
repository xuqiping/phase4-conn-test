package com.superprogrammer.chat.controller;

import com.superprogrammer.chat.dto.MemoryProjectUserGrantVO;
import com.superprogrammer.chat.dto.MemorySearchItemVO;
import com.superprogrammer.chat.service.internal.MemoryProjectUserGrantService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 记忆二期 P1 · 项目↔个人授权端点（只读召回）。
 * <p>
 * 双向发起，落同一 ACTIVE 授权：
 * <ul>
 *   <li>{@code POST /projects/{projectId}/user-grants}（项目主动授权）：body={"userId":n}；仅项目 owner/admin，立即 ACTIVE。</li>
 *   <li>{@code POST /user-grants/apply}（个人申请）：body={"projectId":n}；本人发起 → PENDING 待项目 owner/admin 审批。</li>
 *   <li>{@code GET /user-grants/mine}：我相关的授权（被授权人 或 项目侧 owner/admin）。</li>
 *   <li>{@code POST /user-grants/{id}/approve|reject}：项目 owner/admin。</li>
 *   <li>{@code DELETE /user-grants/{id}}：撤销 ACTIVE（项目 owner/admin 或被授权人本人）/ 取消 PENDING（申请人或项目 owner/admin）。</li>
 * </ul>
 * 权边界细粒度判定内建 {@link MemoryProjectUserGrantService}（承 P1 决策）。
 */
@Slf4j
@RestController
@RequestMapping("/api/chat/memory")
@RequiredArgsConstructor
public class MemoryProjectUserGrantController {

    private final MemoryProjectUserGrantService grantService;

    /** 项目主动授权个人（项目 owner/admin；立即 ACTIVE）。body={"userId": 被授权人}。 */
    @PostMapping("/projects/{projectId}/user-grants")
    public ResponseEntity<R<MemoryProjectUserGrantVO>> grantByProject(@PathVariable Long projectId,
                                                                      @RequestBody Map<String, Long> body) {
        Long userId = body != null ? body.get("userId") : null;
        MemoryProjectUserGrantVO vo = grantService.grantByProject(projectId, userId, requireLogin());
        return ResponseEntity.ok(R.ok("已授权，对方可在召回范围勾选本项目", vo));
    }

    /** 个人申请召回某项目（本人发起 → 待项目 owner/admin 审批）。body={"projectId": 目标项目}。 */
    @PostMapping("/user-grants/apply")
    public ResponseEntity<R<MemoryProjectUserGrantVO>> apply(@RequestBody Map<String, Long> body) {
        Long projectId = body != null ? body.get("projectId") : null;
        if (projectId == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "projectId 必填");
        }
        MemoryProjectUserGrantVO vo = grantService.applyByUser(projectId, requireLogin());
        return ResponseEntity.ok(R.ok("已申请，待项目 owner/admin 审批", vo));
    }

    /** 我相关的授权（前端「我被授权的 / 我管理的项目授权出去的 / 待我审批的申请」三栏数据源）。 */
    @GetMapping("/user-grants/mine")
    public ResponseEntity<R<List<MemoryProjectUserGrantVO>>> listMine() {
        return ResponseEntity.ok(R.ok(grantService.listMine(requireLogin())));
    }

    /** 审批通过（项目 owner/admin）。 */
    @PostMapping("/user-grants/{grantId}/approve")
    public ResponseEntity<R<Void>> approve(@PathVariable Long grantId) {
        grantService.approve(grantId, requireLogin());
        return ResponseEntity.ok(R.ok("已通过，对方可在召回范围勾选本项目", null));
    }

    /** 审批拒绝（项目 owner/admin；30 天内同对个人不可重申）。 */
    @PostMapping("/user-grants/{grantId}/reject")
    public ResponseEntity<R<Void>> reject(@PathVariable Long grantId) {
        grantService.reject(grantId, requireLogin());
        return ResponseEntity.ok(R.ok("已拒绝", null));
    }

    /** 撤销 ACTIVE / 取消 PENDING。 */
    @DeleteMapping("/user-grants/{grantId}")
    public ResponseEntity<R<Void>> revoke(@PathVariable Long grantId) {
        grantService.revoke(grantId, requireLogin());
        return ResponseEntity.ok(R.ok("已撤销", null));
    }

    /** 关键词检索用户（项目授权个人的被授权人选择；仅 id+name，限 10）。 */
    @GetMapping("/user-grants/search-users")
    public ResponseEntity<R<List<MemorySearchItemVO>>> searchUsers(@RequestParam(required = false) String q) {
        return ResponseEntity.ok(R.ok(grantService.searchUsers(q)));
    }

    /** 关键词检索项目（个人申请召回的目标项目选择；仅 id+name，限 10，排除自建）。 */
    @GetMapping("/user-grants/search-projects")
    public ResponseEntity<R<List<MemorySearchItemVO>>> searchProjects(@RequestParam(required = false) String q) {
        return ResponseEntity.ok(R.ok(grantService.searchProjects(q, requireLogin())));
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
