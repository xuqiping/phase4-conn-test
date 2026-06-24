package com.superprogrammer.knowledge.controller;

import com.superprogrammer.auth.security.RequirePermission;
import com.superprogrammer.common.result.PageResult;
import com.superprogrammer.common.result.R;
import com.superprogrammer.knowledge.dto.RagRetrievalLogVO;
import com.superprogrammer.knowledge.service.RagRetrievalLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;

/**
 * rag_retrieval_logs 审计查询/清理（管理员，knowledge:manage）。
 * trace 含用户 query（近似 PII），仅 knowledge:manage（admin）可达。
 *
 * <p>GET /api/knowledge/retrieval-logs?page=&size=&userId=&kbId=&mode=&from=&to=
 * <p>DELETE /api/knowledge/retrieval-logs/{id}
 * <p>DELETE /api/knowledge/retrieval-logs?before=ISO-8601（按时间批量清理）
 */
@RestController
@RequestMapping("/api/knowledge/retrieval-logs")
@RequiredArgsConstructor
public class RagRetrievalLogController {

    private final RagRetrievalLogService ragRetrievalLogService;

    @GetMapping
    @RequirePermission("knowledge:manage")
    public ResponseEntity<R<PageResult<RagRetrievalLogVO>>> page(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) Long kbId,
            @RequestParam(required = false) String mode,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to) {
        return ResponseEntity.ok(R.ok(ragRetrievalLogService.page(userId, kbId, mode, from, to, page, size)));
    }

    @DeleteMapping("/{id}")
    @RequirePermission("knowledge:manage")
    public ResponseEntity<R<Void>> delete(@PathVariable Long id) {
        boolean ok = ragRetrievalLogService.delete(id);
        return ResponseEntity.ok(R.ok(ok ? "删除成功" : "记录不存在", null));
    }

    @DeleteMapping
    @RequirePermission("knowledge:manage")
    public ResponseEntity<R<Integer>> deleteBefore(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime before) {
        int deleted = ragRetrievalLogService.deleteBefore(before);
        return ResponseEntity.ok(R.ok("已清理 " + deleted + " 条早于 " + before + " 的检索记录", deleted));
    }
}
