package com.superprogrammer.chat.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.superprogrammer.chat.dto.MemoryConsolidationScopeRequest;
import com.superprogrammer.chat.dto.MemoryConsolidationTargetView;
import com.superprogrammer.chat.dto.MemoryConsolidationTriggerRequest;
import com.superprogrammer.chat.dto.MemoryConflictVO;
import com.superprogrammer.chat.dto.MemorySummaryConflictResolveRequest;
import com.superprogrammer.chat.entity.MemoryConflict;
import com.superprogrammer.chat.entity.MemoryConsolidationScope;
import com.superprogrammer.chat.mapper.MemoryConsolidationScopeMapper;
import com.superprogrammer.chat.service.internal.MemoryConsolidationService;
import com.superprogrammer.chat.service.internal.MemoryConsolidationService.SummarizeResult;
import com.superprogrammer.chat.service.internal.MemoryConflictResolutionService;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.common.result.R;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 计划12 · E-7 · 总结 + 冲突对外端点（总体设计 §3.4 统一入口 + §3.5 裁决）。
 * <p>
 * <b>偏离 plan</b>：plan 列「改 {@code MemoryController}」——legacy MemoryController 是
 * {@code /api/chat/memories}（plural，user_memories 旧表，H 收尾 404）。新总结/冲突走
 * {@code /api/chat/memory/consolidation} + {@code /api/chat/memory/conflicts}，独立控制器避新旧纠缠
 * （承 C/D/E-3/E-4 隔离裁决）。
 * <p>
 * <b>仅本人</b>（向量 7 IDOR）：所有端点走当前登录 uid，无 uid 入参。
 * <ul>
 *   <li>{@code GET /targets} —— 总结入口弹框数据（{个人} ∪ 已加入项目，hasChange/未覆盖计数/autoEnabled）；</li>
 *   <li>{@code POST /trigger} —— 手动总结（多 scope，先 backfill raw 再压缩，独立于开关）；</li>
 *   <li>{@code GET/PUT /auto} —— 自动总结 scope 勾选（跨会话沿用）；</li>
 *   <li>{@code GET /conflicts/pending} —— 待裁决冲突列表；</li>
 *   <li>{@code POST /conflicts/{id}/resolve} —— 四选项裁决（含 DISCARD 级联）。</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/chat/memory")
@RequiredArgsConstructor
public class MemoryConsolidationController {

    private final MemoryConsolidationService consolidationService;
    private final MemoryConflictResolutionService conflictService;
    private final MemoryConsolidationScopeMapper scopeMapper;

    // ============================ 总结入口 ============================

    /** 总结入口弹框数据（{个人} ∪ 已加入项目，标 hasChange/未覆盖/autoEnabled）。 */
    @GetMapping("/consolidation/targets")
    public ResponseEntity<R<List<MemoryConsolidationTargetView>>> targets() {
        Long uid = requireLogin();
        return ResponseEntity.ok(R.ok(consolidationService.listTargets(uid)));
    }

    /** 手动总结触发（多 scope，每 scope backfill raw + 压缩，独立于开关；CAS 锁与定时 worker 互斥）。 */
    @PostMapping("/consolidation/trigger")
    public ResponseEntity<R<SummarizeResult>> trigger(@Valid @RequestBody MemoryConsolidationTriggerRequest req) {
        Long uid = requireLogin();
        SummarizeResult r = consolidationService.triggerManual(uid, req);
        log.info("手动总结触发 userId={} summaries={} conflicts={} notes={}",
                uid, r.getSummariesWritten(), r.getConflictsCreated(), r.getNotes());
        return ResponseEntity.ok(R.ok(r));
    }

    /** 取自动总结 scope 勾选（PERSONAL + 已配置 PROJECT 行）。 */
    @GetMapping("/consolidation/auto")
    public ResponseEntity<R<List<ScopeAutoView>>> getAutoScopes() {
        Long uid = requireLogin();
        List<MemoryConsolidationScope> rows = scopeMapper.selectList(
                new LambdaQueryWrapper<MemoryConsolidationScope>()
                        .eq(MemoryConsolidationScope::getUserId, uid)
                        .orderByAsc(MemoryConsolidationScope::getScopeKind));
        List<ScopeAutoView> out = new ArrayList<>();
        for (MemoryConsolidationScope r : rows) {
            out.add(new ScopeAutoView(r.getScopeKind(), r.getProjectId(),
                    Boolean.TRUE.equals(r.getAutoEnabled())));
        }
        return ResponseEntity.ok(R.ok(out));
    }

    /** 改自动总结勾选（upsert：PERSONAL 行已存在；PROJECT 新增/翻转 auto_enabled）。 */
    @PutMapping("/consolidation/auto")
    public ResponseEntity<R<Void>> saveAutoScopes(@RequestBody ScopeAutoSaveRequest req) {
        Long uid = requireLogin();
        OffsetDateTime now = OffsetDateTime.now();
        if (req != null && req.getScopes() != null) {
            for (ScopeAutoView v : req.getScopes()) {
                String kind = v.getScopeKind() == null ? "PERSONAL" : v.getScopeKind().toUpperCase();
                if (!"PERSONAL".equals(kind) && !"PROJECT".equals(kind)) {
                    throw new BusinessException(ErrorCode.BAD_REQUEST, "非法 scope_kind: " + v.getScopeKind());
                }
                Long projectId = "PERSONAL".equals(kind) ? null : v.getProjectId();
                scopeMapper.upsertScope(uid, kind, projectId, v.isAutoEnabled(), now);
            }
        }
        log.info("自动总结勾选保存 userId={}", uid);
        return ResponseEntity.ok(R.<Void>ok("已保存", null));
    }

    // ============================ 冲突裁决 ============================

    /** 待裁决冲突列表（面板，向量 6 user_id scope）。 */
    @GetMapping("/conflicts/pending")
    public ResponseEntity<R<List<MemoryConflictVO>>> pendingConflicts() {
        Long uid = requireLogin();
        List<MemoryConflict> list = conflictService.listPending(uid);
        List<MemoryConflictVO> out = new ArrayList<>();
        for (MemoryConflict c : list) {
            MemoryConflictVO vo = new MemoryConflictVO();
            vo.setConflictId(c.getId());
            vo.setStatus(c.getStatus());
            vo.setAskText(c.getAskText());
            vo.setCreatedAt(c.getCreatedAt() == null ? null : c.getCreatedAt().toString());
            vo.setProjectShared(Boolean.TRUE.equals(c.getProjectShared()));
            out.add(vo);
        }
        return ResponseEntity.ok(R.ok(out));
    }

    /** 待裁决冲突计数（badge 轮询）。 */
    @GetMapping("/conflicts/pending-count")
    public ResponseEntity<R<Integer>> pendingCount() {
        Long uid = requireLogin();
        return ResponseEntity.ok(R.ok(conflictService.countPending(uid)));
    }

    /** 四选项裁决（KEEP_BOTH/KEEP_NEW/KEEP_OLD/DISCARD，含 §3.8 级联）。 */
    @PostMapping("/conflicts/{id}/resolve")
    public ResponseEntity<R<Boolean>> resolve(@PathVariable Long id,
                                              @Valid @RequestBody MemorySummaryConflictResolveRequest req) {
        Long uid = requireLogin();
        boolean ok = conflictService.resolve(uid, id, req.getDecision());
        return ResponseEntity.ok(R.ok(ok));
    }

    // ---- DTO（内嵌，仅 controller 视图层用）----

    @lombok.Data
    @lombok.AllArgsConstructor
    @lombok.NoArgsConstructor
    public static class ScopeAutoView {
        private String scopeKind;
        private Long projectId;
        private boolean autoEnabled;
    }

    @lombok.Data
    public static class ScopeAutoSaveRequest {
        private List<ScopeAutoView> scopes;
    }

    // ---- 登录校验 ----

    private Long requireLogin() {
        Long uid = getCurrentUserId();
        if (uid == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "未登录");
        }
        return uid;
    }

    private Long getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getPrincipal() == null) {
            return null;
        }
        return (Long) auth.getPrincipal();
    }
}
