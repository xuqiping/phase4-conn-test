package com.superprogrammer.knowledge.controller;

import com.superprogrammer.auth.security.RequirePermission;
import com.superprogrammer.common.audit.AuditLog;
import com.superprogrammer.common.result.R;
import com.superprogrammer.knowledge.evaluation.EvaluationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
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

/** 管理员维护知识库离线评测数据集与用例。 */
@RestController
@RequestMapping("/api/knowledge/admin/evaluation")
@RequiredArgsConstructor
public class KnowledgeEvaluationController {
    private static final long CURRENT_TENANT_ID = 1L;

    private final EvaluationService evaluationService;

    @PostMapping("/datasets")
    @RequirePermission("knowledge:manage")
    @AuditLog(module = "kb", action = "rag_eval_dataset_create", targetType = "rag_eval_dataset")
    public ResponseEntity<R<EvaluationService.Dataset>> createDataset(@RequestBody DatasetRequest request) {
        EvaluationService.Dataset dataset = evaluationService.createDataset(
                CURRENT_TENANT_ID, request.kbId(), request.name(), request.description(), currentUserId());
        return ResponseEntity.ok(R.ok(dataset));
    }

    @PostMapping(value = "/datasets/{datasetId}/cases/import", consumes = {
            MediaType.APPLICATION_JSON_VALUE, "application/x-ndjson", MediaType.TEXT_PLAIN_VALUE})
    @RequirePermission("knowledge:manage")
    @AuditLog(module = "kb", action = "rag_eval_cases_import", targetType = "rag_eval_dataset")
    public ResponseEntity<R<EvaluationService.ImportResult>> importJsonl(
            @PathVariable long datasetId, @RequestBody String jsonl) {
        return ResponseEntity.ok(R.ok(evaluationService.importJsonl(CURRENT_TENANT_ID, datasetId, jsonl)));
    }

    @GetMapping("/datasets/{datasetId}/cases")
    @RequirePermission("knowledge:manage")
    public ResponseEntity<R<List<EvaluationService.EvalCase>>> listCases(@PathVariable long datasetId) {
        return ResponseEntity.ok(R.ok(evaluationService.listCases(CURRENT_TENANT_ID, datasetId)));
    }

    @GetMapping(value = "/datasets/{datasetId}/cases/export", produces = "application/x-ndjson")
    @RequirePermission("knowledge:manage")
    @AuditLog(module = "kb", action = "rag_eval_cases_export", targetType = "rag_eval_dataset")
    public ResponseEntity<String> exportJsonl(@PathVariable long datasetId) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/x-ndjson"))
                .body(evaluationService.exportJsonl(CURRENT_TENANT_ID, datasetId));
    }

    private long currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Long id)) {
            throw new IllegalStateException("无法识别当前操作人");
        }
        return id;
    }

    public record DatasetRequest(long kbId, String name, String description) {}
}
