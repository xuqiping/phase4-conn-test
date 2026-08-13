package com.superprogrammer.knowledge.controller;

import com.superprogrammer.auth.security.RequirePermission;
import com.superprogrammer.common.audit.AuditLog;
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
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

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
    private final com.superprogrammer.knowledge.opensearch.KnowledgeIndexRebuildService indexRebuildService;
    private final com.superprogrammer.knowledge.migration.RagRolloutService ragRolloutService;
    private final com.superprogrammer.knowledge.migration.RagRolloutReadinessService rolloutReadinessService;
    private final com.superprogrammer.knowledge.retrieval.ShadowComparisonQueryService shadowComparisonQueryService;
    private final com.superprogrammer.knowledge.service.KnowledgeBaseService knowledgeBaseService;

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
        com.superprogrammer.knowledge.opensearch.KnowledgeIndexRebuildService.RebuildStatus rebuild =
                indexRebuildService.latest(kbId);
        return ResponseEntity.ok(R.ok(rebuild == null ? indexOperationsService.status(kbId) : rebuildStatus(rebuild)));
    }

    @PostMapping("/indexes/{kbId}/rebuild")
    @RequirePermission("knowledge:manage")
    @AuditLog(module = "kb", action = "rag_index_rebuild", targetType = "knowledge_base")
    public ResponseEntity<R<com.superprogrammer.knowledge.dto.KnowledgeIndexStatusVO>> rebuildIndex(
            @PathVariable Long kbId, @RequestBody com.superprogrammer.knowledge.dto.KnowledgeIndexOperationRequest request) {
        return ResponseEntity.ok(R.ok(rebuildStatus(indexRebuildService.start(kbId, request.snapshotId(), request.dryRun()))));
    }

    @PostMapping("/indexes/{kbId}/rebuild/cancel")
    @RequirePermission("knowledge:manage")
    @AuditLog(module = "kb", action = "rag_index_rebuild_cancel", targetType = "knowledge_base")
    public ResponseEntity<R<com.superprogrammer.knowledge.dto.KnowledgeIndexStatusVO>> cancelIndexRebuild(
            @PathVariable Long kbId, @RequestBody com.superprogrammer.knowledge.dto.KnowledgeIndexOperationRequest request) {
        return ResponseEntity.ok(R.ok(rebuildStatus(indexRebuildService.cancel(kbId, request.snapshotId()))));
    }

    @PostMapping("/indexes/{kbId}/switch")
    @RequirePermission("knowledge:manage")
    @AuditLog(module = "kb", action = "rag_index_switch", targetType = "knowledge_base")
    public ResponseEntity<R<com.superprogrammer.knowledge.dto.KnowledgeIndexStatusVO>> switchIndex(
            @PathVariable Long kbId, @RequestBody com.superprogrammer.knowledge.dto.KnowledgeIndexOperationRequest request)
            throws java.io.IOException {
        return ResponseEntity.ok(R.ok(indexOperationsService.switchSnapshot(kbId, request.snapshotId(), request.confirmed())));
    }

    @PostMapping("/indexes/{kbId}/rollback")
    @RequirePermission("knowledge:manage")
    @AuditLog(module = "kb", action = "rag_index_rollback", targetType = "knowledge_base")
    public ResponseEntity<R<com.superprogrammer.knowledge.dto.KnowledgeIndexStatusVO>> rollbackIndex(
            @PathVariable Long kbId, @RequestBody com.superprogrammer.knowledge.dto.KnowledgeIndexOperationRequest request)
            throws java.io.IOException {
        return ResponseEntity.ok(R.ok(indexOperationsService.rollback(kbId, request.confirmed())));
    }

    @GetMapping("/rollouts/{kbId}")
    @RequirePermission("knowledge:manage")
    public ResponseEntity<R<com.superprogrammer.knowledge.migration.RagRolloutService.RolloutState>> rolloutStatus(
            @PathVariable Long kbId) {
        return ResponseEntity.ok(R.ok(ragRolloutService.status(kbId)));
    }

    @AuditLog(module = "kb", action = "rag_rollout_update", targetType = "knowledge_base")
    @PostMapping("/rollouts/{kbId}")
    @RequirePermission("knowledge:manage")
    public ResponseEntity<R<com.superprogrammer.knowledge.migration.RagRolloutService.RolloutState>> configureRollout(
            @PathVariable Long kbId, @RequestBody com.superprogrammer.knowledge.dto.RagRolloutRequest request) {
        return ResponseEntity.ok(R.ok(ragRolloutService.configure(kbId, request.percentage(),
                request.configVersion(), currentUserId(), request.confirmed(), rolloutReadinessService.readiness(kbId))));
    }

    @AuditLog(module = "kb", action = "rag_rollout_rollback", targetType = "knowledge_base")
    @PostMapping("/rollouts/{kbId}/rollback")
    @RequirePermission("knowledge:manage")
    public ResponseEntity<R<com.superprogrammer.knowledge.migration.RagRolloutService.RolloutState>> rollbackRollout(
            @PathVariable Long kbId, @RequestBody com.superprogrammer.knowledge.dto.RagRolloutRequest request) {
        return ResponseEntity.ok(R.ok(ragRolloutService.rollback(kbId, currentUserId(), request.confirmed())));
    }

    @GetMapping("/shadow-comparisons")
    @RequirePermission("knowledge:manage")
    public ResponseEntity<R<java.util.List<com.superprogrammer.knowledge.retrieval.ShadowComparisonQueryService.ShadowComparison>>>
    shadowComparisons(Long kbId, String status, Integer limit) {
        com.superprogrammer.knowledge.entity.KnowledgeBase kb = knowledgeBaseService.ensure(kbId);
        return ResponseEntity.ok(R.ok(shadowComparisonQueryService.findRecent(
                kb.getTenantId(), kbId, status, limit == null ? 50 : limit)));
    }

    private long currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Long id)) {
            throw new IllegalStateException("无法识别当前操作者");
        }
        return id;
    }

    private com.superprogrammer.knowledge.dto.KnowledgeIndexStatusVO rebuildStatus(
            com.superprogrammer.knowledge.opensearch.KnowledgeIndexRebuildService.RebuildStatus rebuild) {
        com.superprogrammer.knowledge.dto.KnowledgeIndexStatusVO route = indexOperationsService.status(rebuild.knowledgeBaseId());
        return new com.superprogrammer.knowledge.dto.KnowledgeIndexStatusVO(
                rebuild.knowledgeBaseId(), rebuild.state(), route.readAlias(), route.writeAlias(),
                route.activeSnapshotId(), route.previousSnapshotId(), rebuild.snapshotId(), rebuild.total(),
                rebuild.completed(), rebuild.failed(), rebuild.cancelled());
    }
}
