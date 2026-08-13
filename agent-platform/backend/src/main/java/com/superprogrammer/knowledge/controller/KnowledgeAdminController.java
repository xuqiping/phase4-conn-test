package com.superprogrammer.knowledge.controller;

import com.superprogrammer.auth.security.RequirePermission;
import com.superprogrammer.common.result.R;
import com.superprogrammer.knowledge.service.KnowledgeNodeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 知识库管理员端点（knowledge:manage）。
 *
 * <p>POST /api/knowledge/admin/backfill-tokens — 回填存量节点 content_tokens（Phase2 V35 jieba-BM25 词法兜底）。
 * <p>POST /api/knowledge/admin/backfill-l1-embeddings — 存量文档入队 UPSERT_L1 job 建 L1 向量（Phase3 V36）。
 */
@RestController
@RequestMapping("/api/knowledge/admin")
@RequiredArgsConstructor
public class KnowledgeAdminController {

    private final KnowledgeNodeService knowledgeNodeService;
    private final com.superprogrammer.knowledge.opensearch.KnowledgeIndexOperationsService indexOperationsService;

    @PostMapping("/backfill-tokens")
    @RequirePermission("knowledge:manage")
    public ResponseEntity<R<Integer>> backfillTokens() {
        int updated = knowledgeNodeService.backfillContentTokens();
        return ResponseEntity.ok(R.ok("已回填 " + updated + " 个节点的 content_tokens", updated));
    }

    @PostMapping("/backfill-l1-embeddings")
    @RequirePermission("knowledge:manage")
    public ResponseEntity<R<Integer>> backfillL1Embeddings() {
        int enqueued = knowledgeNodeService.backfillL1Embeddings();
        return ResponseEntity.ok(R.ok("已入队 " + enqueued + " 个文档的 L1 向量 job（异步 embed）", enqueued));
    }

    @GetMapping("/indexes/{kbId}")
    @RequirePermission("knowledge:manage")
    public ResponseEntity<R<com.superprogrammer.knowledge.dto.KnowledgeIndexStatusVO>> indexStatus(@PathVariable Long kbId) {
        return ResponseEntity.ok(R.ok(indexOperationsService.status(kbId)));
    }

    @PostMapping("/indexes/{kbId}/rebuild")
    @RequirePermission("knowledge:manage")
    public ResponseEntity<R<com.superprogrammer.knowledge.dto.KnowledgeIndexStatusVO>> rebuildIndex(
            @PathVariable Long kbId, @RequestBody com.superprogrammer.knowledge.dto.KnowledgeIndexOperationRequest request) {
        return ResponseEntity.ok(R.ok(indexOperationsService.registerRebuild(kbId, request.snapshotId(), request.dryRun())));
    }

    @PostMapping("/indexes/{kbId}/switch")
    @RequirePermission("knowledge:manage")
    public ResponseEntity<R<com.superprogrammer.knowledge.dto.KnowledgeIndexStatusVO>> switchIndex(
            @PathVariable Long kbId, @RequestBody com.superprogrammer.knowledge.dto.KnowledgeIndexOperationRequest request)
            throws java.io.IOException {
        return ResponseEntity.ok(R.ok(indexOperationsService.switchSnapshot(kbId, request.snapshotId(), request.confirmed())));
    }

    @PostMapping("/indexes/{kbId}/rollback")
    @RequirePermission("knowledge:manage")
    public ResponseEntity<R<com.superprogrammer.knowledge.dto.KnowledgeIndexStatusVO>> rollbackIndex(
            @PathVariable Long kbId, @RequestBody com.superprogrammer.knowledge.dto.KnowledgeIndexOperationRequest request)
            throws java.io.IOException {
        return ResponseEntity.ok(R.ok(indexOperationsService.rollback(kbId, request.confirmed())));
    }
}
