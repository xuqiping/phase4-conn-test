package com.superprogrammer.chat.controller;

import com.superprogrammer.chat.dto.MemoryConflictResolveRequest;
import com.superprogrammer.chat.dto.MemoryConflictVO;
import com.superprogrammer.chat.dto.UserMemoryVO;
import com.superprogrammer.chat.service.MemoryConflictService;
import com.superprogrammer.chat.service.MemoryService;
import com.superprogrammer.common.result.R;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

    /** 列出当前用户全部记忆（按 updatedAt 倒序）。 */
    @GetMapping
    public ResponseEntity<R<List<UserMemoryVO>>> list() {
        return ResponseEntity.ok(R.ok(memoryService.listMemories(getCurrentUserId())));
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

    // ---- 记忆冲突（V27）----

    /** 列当前用户 FLAGGED 冲突（按 conflict 分组）。 */
    @GetMapping("/conflicts")
    public ResponseEntity<R<List<MemoryConflictVO>>> listConflicts() {
        return ResponseEntity.ok(R.ok(conflictService.listFlagged(getCurrentUserId())));
    }

    /** 手动解决一条冲突（KEEP_NEW/KEEP_OLD/KEEP_BOTH/DISCARD）。 */
    @PutMapping("/conflicts/{id}/resolve")
    public ResponseEntity<R<Void>> resolveConflict(@PathVariable Long id,
                                                   @Valid @RequestBody MemoryConflictResolveRequest req) {
        boolean ok = conflictService.resolve(getCurrentUserId(), id, req.getDecision());
        return ResponseEntity.ok(R.ok(ok ? "已解决" : "冲突不存在或无权操作", null));
    }

    private Long getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? null : (Long) auth.getPrincipal();
    }
}
