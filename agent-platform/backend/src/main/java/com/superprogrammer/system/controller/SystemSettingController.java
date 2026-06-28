package com.superprogrammer.system.controller;

import com.superprogrammer.auth.security.RequirePermission;
import com.superprogrammer.chat.service.MemoryService;
import com.superprogrammer.common.result.R;
import com.superprogrammer.system.dto.AuthSettingsUpdateRequest;
import com.superprogrammer.system.dto.AuthSettingsVO;
import com.superprogrammer.system.dto.RagMemorySettingsUpdateRequest;
import com.superprogrammer.system.dto.RagMemorySettingsVO;
import com.superprogrammer.system.dto.RagRecallSettingsUpdateRequest;
import com.superprogrammer.system.dto.RagRecallSettingsVO;
import com.superprogrammer.system.service.SystemSettingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/system/settings")
@RequiredArgsConstructor
public class SystemSettingController {
    private final SystemSettingService service;
    private final MemoryService memoryService;

    @GetMapping("/auth")
    @RequirePermission("role:manage")
    public ResponseEntity<R<AuthSettingsVO>> getAuthSettings() {
        return ResponseEntity.ok(R.ok(service.getAuthSettings()));
    }

    @PutMapping("/auth")
    @RequirePermission("role:manage")
    public ResponseEntity<R<AuthSettingsVO>> updateAuthSettings(
            @Valid @RequestBody AuthSettingsUpdateRequest request) {
        return ResponseEntity.ok(R.ok(service.updateAuthSettings(request.getAccessTokenExpirationMs())));
    }

    // ---- RAG/记忆模式全局开关（V26）----

    @GetMapping("/rag-memory")
    @RequirePermission("role:manage")
    public ResponseEntity<R<RagMemorySettingsVO>> getRagMemorySettings() {
        return ResponseEntity.ok(R.ok(RagMemorySettingsVO.builder()
                .enabled(service.getRagMemoryEnabled())
                .processMode(service.getMemoryProcessMode())
                .retrievalMode(service.getMemoryRetrievalMode())
                .keyLanguage(service.getMemoryKeyLanguage())
                .fullContextThreshold(service.getMemoryFullContextThreshold())
                .keywordPerBlockThreshold(service.getMemoryKeywordPerBlockThreshold())
                .llmKeyCoarseTopN(service.getLlmKeyCoarseTopN())
                .llmKeyRerank(service.getLlmKeyRerank())
                .keywordMax(service.getKeywordMax()).build()));
    }

    @PutMapping("/rag-memory")
    @RequirePermission("role:manage")
    public ResponseEntity<R<RagMemorySettingsVO>> updateRagMemorySettings(
            @Valid @RequestBody RagMemorySettingsUpdateRequest request) {
        service.updateRagMemoryEnabled(request.getEnabled());
        service.updateMemoryProcessMode(request.getProcessMode());
        service.updateMemoryRetrievalMode(request.getRetrievalMode());
        if (request.getKeyLanguage() != null) service.updateMemoryKeyLanguage(request.getKeyLanguage());
        if (request.getFullContextThreshold() != null) {
            service.updateMemoryFullContextThreshold(request.getFullContextThreshold());
        }
        if (request.getKeywordPerBlockThreshold() != null) {
            service.updateMemoryKeywordPerBlockThreshold(request.getKeywordPerBlockThreshold());
        }
        if (request.getLlmKeyCoarseTopN() != null) {
            service.updateLlmKeyCoarseTopN(request.getLlmKeyCoarseTopN());
        }
        if (request.getLlmKeyRerank() != null) {
            service.updateLlmKeyRerank(request.getLlmKeyRerank());
        }
        if (request.getKeywordMax() != null) {
            service.updateKeywordMax(request.getKeywordMax());
        }
        return ResponseEntity.ok(R.ok("RAG/记忆模式开关已更新",
                RagMemorySettingsVO.builder()
                        .enabled(service.getRagMemoryEnabled())
                        .processMode(service.getMemoryProcessMode())
                        .retrievalMode(service.getMemoryRetrievalMode())
                        .keyLanguage(service.getMemoryKeyLanguage())
                        .fullContextThreshold(service.getMemoryFullContextThreshold())
                        .keywordPerBlockThreshold(service.getMemoryKeywordPerBlockThreshold())
                        .llmKeyCoarseTopN(service.getLlmKeyCoarseTopN())
                        .llmKeyRerank(service.getLlmKeyRerank())
                        .keywordMax(service.getKeywordMax()).build()));
    }

    // ---- RAG 召回 query 扩展全局开关（4 路同读：/retrieve、/ask、Chat、Agent/工作流）----

    @GetMapping("/rag-recall")
    @RequirePermission("role:manage")
    public ResponseEntity<R<RagRecallSettingsVO>> getRagRecallSettings() {
        return ResponseEntity.ok(R.ok(RagRecallSettingsVO.builder()
                .enabled(service.getRagRecallExpansionEnabled())
                .threshold(service.getRagRecallExpansionThreshold()).build()));
    }

    @PutMapping("/rag-recall")
    @RequirePermission("role:manage")
    public ResponseEntity<R<RagRecallSettingsVO>> updateRagRecallSettings(
            @Valid @RequestBody RagRecallSettingsUpdateRequest request) {
        service.updateRagRecallExpansionEnabled(request.getEnabled());
        if (request.getThreshold() != null) {
            service.updateRagRecallExpansionThreshold(request.getThreshold());
        }
        return ResponseEntity.ok(R.ok("RAG 召回扩展设置已更新",
                RagRecallSettingsVO.builder()
                        .enabled(service.getRagRecallExpansionEnabled())
                        .threshold(service.getRagRecallExpansionThreshold()).build()));
    }

    /**
     * 老记忆实体标签回填（V31 迁移补丁）：异步为 entities IS NULL 的老记忆批量抽实体。
     * 幂等可重跑；无 HTTP 超时（fire-and-forget）。进度见后端日志 memoryBackfill。
     */
    @PostMapping("/rag-memory/backfill-entities")
    @RequirePermission("role:manage")
    public ResponseEntity<R<String>> backfillMemoryEntities() {
        memoryService.backfillEntitiesAsync();
        return ResponseEntity.ok(R.ok("已启动老记忆实体回填（异步），进度见后端日志 memoryBackfill", "STARTED"));
    }

    /**
     * 历史记忆冲突脏数据清理：把 conflict 已 RESOLVED 但记忆行仍带 conflict_id 的残留（旧 KEEP_BOTH
     * "双行共存"遗留）按 (user, key) 合并成一条 clean。异步、幂等可重跑。进度见后端日志 memoryCleanup。
     */
    @PostMapping("/rag-memory/cleanup-memory-residue")
    @RequirePermission("role:manage")
    public ResponseEntity<R<String>> cleanupMemoryResidue() {
        memoryService.cleanupResolvedResidueAsync();
        return ResponseEntity.ok(R.ok("已启动记忆冲突残留清理（异步），进度见后端日志 memoryCleanup", "STARTED"));
    }
}
