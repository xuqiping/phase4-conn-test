package com.superprogrammer.chat.controller;

import com.superprogrammer.chat.dto.MemoryLifecycleActionVO;
import com.superprogrammer.chat.dto.MemoryLifecycleProjectVO;
import com.superprogrammer.chat.dto.MemoryLifecyclePullRequest;
import com.superprogrammer.chat.service.internal.MemoryLifecycleService;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.common.result.R;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 计划12 · F-4b 前置 · 生命周期折叠板端点（总体设计 §3.7，C/E 收尾遗漏补齐）。
 * <p>
 * <ul>
 *   <li>{@code GET /departed-projects} —— 本人已离开项目列表（copy-to 折叠板源数据）。</li>
 *   <li>{@code POST /departed-projects/{projectId}/copy-to} —— 拉取到自建新项目（copy 非 move）。</li>
 *   <li>{@code GET /deleted-projects} —— 本人已删除项目列表（restore 折叠板源数据，badge 高亮依据）。</li>
 *   <li>{@code POST /deleted-projects/{projectId}/restore} —— 拉取到自建新项目（仅拉 turn 不拉 summary）。</li>
 * </ul>
 * <p>
 * <b>偏离 plan</b>：独立控制器走 {@code /api/chat/memory} 新栈命名空间（同 MemoryRosterController），
 * 零碰 legacy {@code /api/chat/memories}（承 C/D/E/I2 隔离裁决）。
 */
@Slf4j
@RestController
@RequestMapping("/api/chat/memory")
@RequiredArgsConstructor
public class MemoryLifecycleController {

    private final MemoryLifecycleService lifecycleService;

    /** 本人已离开项目列表（DEPARTED membership + 可拉取 turn 计数）。 */
    @GetMapping("/departed-projects")
    public ResponseEntity<R<List<MemoryLifecycleProjectVO>>> listDepartedProjects() {
        Long uid = requireLogin();
        return ResponseEntity.ok(R.ok(lifecycleService.listDepartedProjects(uid)));
    }

    /** copy-to：已离开项目记忆拉取到自建新项目（仅 DEPARTED 本人，否则 403）。 */
    @PostMapping("/departed-projects/{projectId}/copy-to")
    public ResponseEntity<R<MemoryLifecycleActionVO>> copyDepartedProjectTo(
            @PathVariable Long projectId,
            @RequestBody(required = false) MemoryLifecyclePullRequest req) {
        Long uid = requireLogin();
        MemoryLifecycleActionVO vo = lifecycleService.copyDepartedProjectTo(
                uid, projectId, req == null ? null : req.getProjectName());
        return ResponseEntity.ok(R.ok("已拉取到新项目", vo));
    }

    /** 本人已删除项目列表（deleted_project_ids 引用 + 待拉取 turn 计数）。 */
    @GetMapping("/deleted-projects")
    public ResponseEntity<R<List<MemoryLifecycleProjectVO>>> listDeletedProjects() {
        Long uid = requireLogin();
        return ResponseEntity.ok(R.ok(lifecycleService.listDeletedProjects(uid)));
    }

    /** restore：已删除项目记忆拉取到自建新项目（仅拉 turn；无待拉取 → 404）。 */
    @PostMapping("/deleted-projects/{projectId}/restore")
    public ResponseEntity<R<MemoryLifecycleActionVO>> restoreDeletedProject(
            @PathVariable Long projectId,
            @RequestBody(required = false) MemoryLifecyclePullRequest req) {
        Long uid = requireLogin();
        MemoryLifecycleActionVO vo = lifecycleService.restoreDeletedProject(
                uid, projectId, req == null ? null : req.getProjectName());
        return ResponseEntity.ok(R.ok("已拉取到新项目", vo));
    }

    private Long requireLogin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getPrincipal() == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "未登录");
        }
        return (Long) auth.getPrincipal();
    }
}
