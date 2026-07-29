package com.superprogrammer.system.controller;

import com.superprogrammer.auth.security.RequirePermission;
import com.superprogrammer.common.result.R;
import com.superprogrammer.search.service.WebSearchService;
import com.superprogrammer.system.dto.AuthSettingsUpdateRequest;
import com.superprogrammer.system.dto.AuthSettingsVO;
import com.superprogrammer.system.dto.RagMemorySettingsUpdateRequest;
import com.superprogrammer.system.dto.RagMemorySettingsVO;
import com.superprogrammer.system.dto.RagRecallSettingsUpdateRequest;
import com.superprogrammer.system.dto.RagRecallSettingsVO;
import com.superprogrammer.system.dto.WebSearchSettingsUpdateRequest;
import com.superprogrammer.system.dto.WebSearchSettingsVO;
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
    private final WebSearchService webSearchService;

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
                .keywordMax(service.getKeywordMax())
                .entitiesConfig(service.getMemoryEntitiesConfig())
                .genPersonalEnabled(service.getMemoryGenPersonalEnabled()).build()));
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
        if (request.getEntitiesConfig() != null) {
            service.updateMemoryEntitiesConfig(request.getEntitiesConfig());
        }
        if (request.getGenPersonalEnabled() != null) {
            service.updateMemoryGenPersonalEnabled(request.getGenPersonalEnabled());
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
                        .keywordMax(service.getKeywordMax())
                        .entitiesConfig(service.getMemoryEntitiesConfig())
                .genPersonalEnabled(service.getMemoryGenPersonalEnabled()).build()));
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

    // ---- 联网搜索运维配置（provider 下拉 + 各 key 输入 + max/timeout + 总开关 + 测试连通）----

    @GetMapping("/web-search")
    @RequirePermission("role:manage")
    public ResponseEntity<R<WebSearchSettingsVO>> getWebSearchSettings() {
        return ResponseEntity.ok(R.ok(buildWebSearchVO()));
    }

    @PutMapping("/web-search")
    @RequirePermission("role:manage")
    public ResponseEntity<R<WebSearchSettingsVO>> updateWebSearchSettings(
            @Valid @RequestBody WebSearchSettingsUpdateRequest req) {
        if (req.getEnabled() != null) {
            service.updateSearchEnabled(req.getEnabled());
        }
        if (req.getActiveProvider() != null) {
            service.updateActiveSearchProvider(req.getActiveProvider());
        }
        if (req.getMaxResults() != null) {
            service.updateSearchMaxResults(req.getMaxResults());
        }
        if (req.getTimeoutMs() != null) {
            service.updateSearchTimeoutMs(req.getTimeoutMs());
        }
        // key：null=不改；空串=清除；非空=AES 加密 upsert（不回显明文）
        if (req.getTavilyKey() != null) {
            if (req.getTavilyKey().isBlank()) service.clearSearchApiKey("tavily");
            else service.upsertSearchApiKey("tavily", req.getTavilyKey());
        }
        if (req.getSerperKey() != null) {
            if (req.getSerperKey().isBlank()) service.clearSearchApiKey("serper");
            else service.upsertSearchApiKey("serper", req.getSerperKey());
        }
        if (req.getBingKey() != null) {
            if (req.getBingKey().isBlank()) service.clearSearchApiKey("bing");
            else service.upsertSearchApiKey("bing", req.getBingKey());
        }
        return ResponseEntity.ok(R.ok("联网搜索配置已更新", buildWebSearchVO()));
    }

    /** 测试连通：调 active provider 搜 "test"，返回结果数 + 实际命中 provider（验证降级链）。 */
    @PostMapping("/web-search/test")
    @RequirePermission("role:manage")
    public ResponseEntity<R<java.util.Map<String, Object>>> testWebSearch() {
        java.util.List<com.superprogrammer.search.dto.SearchResult> results = webSearchService.search("test");
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("results", results.size());
        out.put("providerAvailability", webSearchService.providerAvailability());
        out.put("activeProvider", service.getActiveSearchProvider());
        out.put("enabled", service.getSearchEnabled());
        return ResponseEntity.ok(R.ok("测试完成（results=命中数，0=零结果或全降级失败）", out));
    }

    private WebSearchSettingsVO buildWebSearchVO() {
        java.util.Map<String, Boolean> avail = webSearchService.providerAvailability();
        return WebSearchSettingsVO.builder()
                .enabled(service.getSearchEnabled())
                .activeProvider(service.getActiveSearchProvider())
                .maxResults(service.getSearchMaxResults())
                .timeoutMs(service.getSearchTimeoutMs())
                .hasTavilyKey(service.getSearchApiKey("tavily") != null)
                .hasSerperKey(service.getSearchApiKey("serper") != null)
                .hasBingKey(service.getSearchApiKey("bing") != null)
                .builtinConfigured(Boolean.TRUE.equals(avail.get("builtin")))
                .providerAvailability(avail)
                .build();
    }
}
