package com.superprogrammer.chat.controller;

import com.superprogrammer.chat.dto.ChatRagModeVO;
import com.superprogrammer.chat.dto.MemoryEditRequest;
import com.superprogrammer.chat.dto.MemoryConflictBatchResolveRequest;
import com.superprogrammer.chat.dto.MemoryConflictResolveRequest;
import com.superprogrammer.chat.dto.MemoryConflictVO;
import com.superprogrammer.chat.dto.MemoryScopeUpdateRequest;
import com.superprogrammer.chat.dto.UserMemoryVO;
import com.superprogrammer.chat.service.MemoryConflictService;
import com.superprogrammer.chat.service.MemoryService;
import com.superprogrammer.common.result.R;
import com.superprogrammer.system.service.SystemSettingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用户长期记忆自服务（查询/删除）。
 * 数据按 current userId 隔离，无需 knowledge/chat 权限——记忆是用户私有资产。
 * 对应 memory gap：原仅 MemoryService 内部 buildMemoryContext/extractMemoriesAsync，无对外端点。
 */
@RestController
@RequestMapping("/api/chat/memories")
@RequiredArgsConstructor
public class MemoryController {

    private final MemoryService memoryService;
    private final MemoryConflictService conflictService;
    private final SystemSettingService systemSettingService;

    /**
     * 用户侧只读：全局记忆开关（会话级开关 null=继承此值，供前端联动显示）。
     * 非 admin——写入走 {@code PUT /system/settings/rag-memory}（role:manage）。
     */
    @GetMapping("/rag-mode")
    public ResponseEntity<R<ChatRagModeVO>> ragMode() {
        return ResponseEntity.ok(R.ok(ChatRagModeVO.builder()
                .globalEnabled(systemSettingService.getRagMemoryEnabled()).build()));
    }

    /** 列出当前用户全部记忆（按 updatedAt 倒序）。 */
    @GetMapping
    public ResponseEntity<R<List<UserMemoryVO>>> list() {
        return ResponseEntity.ok(R.ok(memoryService.listMemories(getCurrentUserId())));
    }

    /** 行内编辑记忆（M1）：改 key/key_zh/value/block_label，后端按需重 embed + home-aware 重复检查。 */
    @PutMapping("/{id}")
    public ResponseEntity<R<UserMemoryVO>> update(@PathVariable Long id, @RequestBody MemoryEditRequest req) {
        return ResponseEntity.ok(R.ok("记忆已更新", memoryService.updateMemory(getCurrentUserId(), id, req)));
    }

    /** 删除单条记忆（非本人返回 404 语义：false → NOT_FOUND）。 */
    @DeleteMapping("/{id}")
    public ResponseEntity<R<Void>> delete(@PathVariable Long id) {
        boolean ok = memoryService.deleteMemory(getCurrentUserId(), id);
        return ResponseEntity.ok(R.ok(ok ? "删除成功" : "记忆不存在或无权操作", null));
    }

    /** 清空当前用户全部记忆。 */
    @DeleteMapping
    public ResponseEntity<R<Integer>> clear() {
        int deleted = memoryService.clearMemories(getCurrentUserId());
        return ResponseEntity.ok(R.ok("已清空 " + deleted + " 条记忆", deleted));
    }

    /** 批量删除记忆（ownership 过滤，只删本人 id）。返实际删除条数。 */
    @DeleteMapping("/batch")
    public ResponseEntity<R<Integer>> deleteBatch(@RequestBody List<Long> ids) {
        int deleted = memoryService.deleteMemories(getCurrentUserId(), ids);
        return ResponseEntity.ok(R.ok("已删除 " + deleted + " 条记忆", deleted));
    }

    /**
     * 记忆注入预览（调试用）：传测试 query，返回三个检索设置当前值 + 记忆总数 + 是否两阶段 +
     * 实际注入 LLM 的上下文文本。供前端面板直观看设置是否生效。
     */
    @PostMapping("/preview")
    public ResponseEntity<R<com.superprogrammer.chat.dto.MemoryContextPreviewVO>> preview(
            @RequestBody com.superprogrammer.chat.dto.MemoryPreviewRequest req) {
        Long userId = getCurrentUserId();
        String q = req == null ? null : req.getQuery();
        // scope：省略 → global-only（向后兼容）；带 includeGlobal/projectIds → 对应读 scope
        boolean includeGlobal = req == null || req.getIncludeGlobal() == null || req.getIncludeGlobal();
        java.util.List<Long> pids = req == null ? java.util.List.of() : req.getProjectIds();
        com.superprogrammer.chat.service.internal.MemoryScope scope =
                new com.superprogrammer.chat.service.internal.MemoryScope(userId, includeGlobal, pids);
        return ResponseEntity.ok(R.ok(memoryService.previewContext(scope, q)));
    }

    /** 回显单条记忆的 scope 归属（面板编辑用）。 */
    @GetMapping("/{id}/scopes")
    public ResponseEntity<R<MemoryService.MemoryScopeVO>> getScopes(@PathVariable Long id) {
        return ResponseEntity.ok(R.ok(memoryService.getMemoryScopes(getCurrentUserId(), id)));
    }

    /** 替换单条记忆的全部 scope 归属（统一覆盖：升级 global / 加项目 / 关 global）。 */
    @PutMapping("/{id}/scopes")
    public ResponseEntity<R<MemoryService.MemoryScopeVO>> updateScopes(
            @PathVariable Long id, @RequestBody MemoryScopeUpdateRequest body) {
        boolean isGlobal = body == null || body.getIsGlobal() == null || body.getIsGlobal();
        java.util.List<Long> pids = body == null ? java.util.List.of() : body.getProjectIds();
        return ResponseEntity.ok(R.ok("scope 已更新",
                memoryService.updateMemoryScopes(getCurrentUserId(), id, isGlobal, pids)));
    }

    // ---- 记忆冲突（V27）----

    /** 列当前用户待处理冲突（PENDING+FLAGGED，按 conflict 分组）。 */
    @GetMapping("/conflicts")
    public ResponseEntity<R<List<MemoryConflictVO>>> listConflicts() {
        return ResponseEntity.ok(R.ok(conflictService.listActive(getCurrentUserId())));
    }

    /** 取并清除记忆写入异常（前端轮询弹一次即清）。data=异常消息 or null。 */
    @GetMapping("/incident")
    public ResponseEntity<R<String>> incident() {
        return ResponseEntity.ok(R.ok(memoryService.getAndClearIncident(getCurrentUserId())));
    }

    /** 记忆处理状态（状态条 3s 轮询）：processingCount=进行中抽取数，conflictCount=待处理冲突数。 */
    @GetMapping("/status")
    public ResponseEntity<R<com.superprogrammer.chat.dto.MemoryStatusVO>> status() {
        return ResponseEntity.ok(R.ok(memoryService.getMemoryStatus(getCurrentUserId())));
    }

    /** 手动解决一条冲突（KEEP_NEW/KEEP_OLD/KEEP_BOTH/DISCARD）。返 data=是否成功。 */
    @PutMapping("/conflicts/{id}/resolve")
    public ResponseEntity<R<Boolean>> resolveConflict(@PathVariable Long id,
                                                     @Valid @RequestBody MemoryConflictResolveRequest req) {
        boolean ok = conflictService.resolve(getCurrentUserId(), id, req.getDecision());
        return ResponseEntity.ok(R.ok(ok ? "已解决" : "冲突不存在或无权操作", ok));
    }

    /** 批量解决当前用户全部 PENDING+FLAGGED 冲突（统一 decision）。 */
    @PostMapping("/conflicts/batch-resolve")
    public ResponseEntity<R<Integer>> batchResolve(@Valid @RequestBody MemoryConflictBatchResolveRequest req) {
        int n = conflictService.resolveAll(getCurrentUserId(), req.getDecision());
        return ResponseEntity.ok(R.ok("批量解决 " + n + " 条冲突", n));
    }

    private Long getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? null : (Long) auth.getPrincipal();
    }
}
