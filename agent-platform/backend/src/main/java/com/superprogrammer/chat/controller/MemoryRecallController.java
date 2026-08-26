package com.superprogrammer.chat.controller;

import com.superprogrammer.chat.dto.MemoryRecallPreviewRequest;
import com.superprogrammer.chat.dto.MemoryRecallResult;
import com.superprogrammer.chat.dto.MemoryRecallScopeRequest;
import com.superprogrammer.chat.dto.MemoryRecallScopeView;
import com.superprogrammer.chat.service.internal.MemoryProjectUserGrantService;
import com.superprogrammer.chat.service.internal.MemoryRecallPipeline;
import com.superprogrammer.chat.service.internal.MemoryRecallScopePreferenceService;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.common.audit.AuditLog;
import com.superprogrammer.common.result.R;
import com.superprogrammer.project.entity.Project;
import com.superprogrammer.project.mapper.ProjectMapper;
import com.superprogrammer.project.service.ProjectService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 计划12 · D-7 · 召回对外端点（总体设计 §3.3 + 运维「preview 透出」）。
 * <p>
 * <b>偏离 plan</b>：plan 列「改 MemoryController」——legacy MemoryController 是 {@code /api/chat/memories}
 * （plural，user_memories 旧表，H 收尾 404）。新召回走 {@code /api/chat/memory/recall}（同 MemoryTurnController
 * 命名空间），独立控制器避免新旧纠缠（承 C/D 裁决）。
 * <p>
 * <b>仅本人</b>（向量 7 IDOR）：所有端点走当前登录用户 uid，无 uid 入参；scope 偏好 1:1 user_id 天然隔离。
 * <ul>
 *   <li>{@code POST /preview} —— 召回并透出 {@link MemoryRecallResult}（assembledText + selectedTags + counts + degraded + traceId）。</li>
 *   <li>{@code GET /scope} —— 取上次 scope 偏好（无历史默认 {个人}，设计 line 113）。</li>
 *   <li>{@code PUT /scope} —— 保存 scope 偏好（跨会话沿用）。</li>
 * </ul>
 *
 * @see MemoryRecallPipeline 召回主流程
 * @see MemoryRecallScopePreferenceService scope 持久化
 */
@Slf4j
@RestController
@RequestMapping("/api/chat/memory/recall")
@RequiredArgsConstructor
public class MemoryRecallController {

    private final MemoryRecallPipeline pipeline;
    private final MemoryRecallScopePreferenceService prefService;
    private final ProjectService projectService;
    private final ProjectMapper projectMapper;
    private final MemoryProjectUserGrantService grantService;

    /** 召回 preview（透出装配文本 + 选中标签 + counts + 降级标记 + traceId）。 */
    @PostMapping("/preview")
    public ResponseEntity<R<MemoryRecallResult>> preview(@RequestBody(required = false) MemoryRecallPreviewRequest req) {
        Long uid = requireLogin();
        String query = req == null ? null : req.getQuery();
        if (query == null || query.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "query 必填");
        }
        MemoryRecallScopeRequest scopeReq = req.getScope();
        MemoryRecallResult result = pipeline.recall(query, scopeReq, uid, null);
        log.info("recall preview userId={} traceId={} summaryCount={} turnCount={} degraded={}",
                uid, result.getTraceId(), result.getSummaryCount(), result.getTurnCount(), result.isDegraded());
        return ResponseEntity.ok(R.ok(result));
    }

    /**
     * 取上次 scope 偏好 + 用户可勾选项目集；无历史默认 {个人}（设计 §3.3 line 113）。
     * <p>
     * 记忆二期 P1：{@code availableProjects} = 本人可访问项目（viaGrant=false）∪ 被授权召回项目（viaGrant=true）。
     * 修正此前 getScope 仅返 Request（无 availableProjects）→ 前端项目勾选下拉恒空的历史缺口。
     */
    @GetMapping("/scope")
    public ResponseEntity<R<MemoryRecallScopeView>> getScope() {
        Long uid = requireLogin();
        MemoryRecallScopeRequest base = prefService.getScope(uid);
        if (base == null) {
            base = defaultPersonalOnly();
        }
        return ResponseEntity.ok(R.ok(buildScopeView(base, uid)));
    }

    /** 持久化偏好回显 View（含 availableProjects，供前端即时刷新可勾选项）。 */
    private MemoryRecallScopeView buildScopeView(MemoryRecallScopeRequest base, Long uid) {
        Set<Long> accessible = projectService.listAccessibleProjectIds(uid);
        List<Long> granted = grantService.findActiveGrantedProjectIds(uid);

        // 一次性批量查项目名（accessible ∪ granted），防 N+1
        List<Long> allIds = new ArrayList<>(accessible);
        for (Long g : granted) {
            if (!accessible.contains(g)) {
                allIds.add(g);
            }
        }
        Map<Long, String> nameMap = new HashMap<>();
        if (!allIds.isEmpty()) {
            for (Project p : projectMapper.selectBatchIds(allIds)) {
                nameMap.put(p.getId(), p.getName());
            }
        }

        List<MemoryRecallScopeView.ProjectOption> options = new ArrayList<>();
        for (Long pid : accessible) {
            options.add(MemoryRecallScopeView.ProjectOption.builder()
                    .projectId(pid).name(nameMap.getOrDefault(pid, "项目#" + pid)).viaGrant(false).build());
        }
        for (Long pid : granted) {
            if (!accessible.contains(pid)) {
                options.add(MemoryRecallScopeView.ProjectOption.builder()
                        .projectId(pid).name(nameMap.getOrDefault(pid, "项目#" + pid)).viaGrant(true).build());
            }
        }

        return MemoryRecallScopeView.builder()
                .personalOn(base.getPersonalOn() == null || base.getPersonalOn())
                .projectIds(base.getProjectIds())
                .direction(base.getDirection())
                .relativeDays(base.getRelativeDays())
                .start(base.getStart())
                .end(base.getEnd())
                .includeDeparted(base.getIncludeDeparted() == null || base.getIncludeDeparted())
                .availableProjects(options)
                .build();
    }

    /** 保存 scope 偏好（跨会话沿用）。 */
    @PutMapping("/scope")
    @AuditLog(module = "memory", action = "recall_scope_set", targetType = "memory_config")
    public ResponseEntity<R<Void>> saveScope(@RequestBody(required = false) MemoryRecallScopeRequest req) {
        Long uid = requireLogin();
        prefService.saveScope(uid, req);
        log.info("recall scope saved userId={}", uid);
        return ResponseEntity.ok(R.<Void>ok("已保存", null));
    }

    private Long requireLogin() {
        Long uid = getCurrentUserId();
        if (uid == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "未登录");
        }
        return uid;
    }

    private static MemoryRecallScopeRequest defaultPersonalOnly() {
        MemoryRecallScopeRequest r = new MemoryRecallScopeRequest();
        r.setPersonalOn(true);
        return r;
    }

    private Long getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getPrincipal() == null) {
            return null;
        }
        return (Long) auth.getPrincipal();
    }
}
