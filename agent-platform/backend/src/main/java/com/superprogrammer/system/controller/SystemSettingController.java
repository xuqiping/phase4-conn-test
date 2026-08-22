package com.superprogrammer.system.controller;

import com.superprogrammer.common.audit.AuditLog;
import com.superprogrammer.auth.security.RequirePermission;
import com.superprogrammer.common.result.R;
import com.superprogrammer.search.service.WebSearchService;
import com.superprogrammer.system.dto.AuthSettingsUpdateRequest;
import com.superprogrammer.system.dto.AuthSettingsVO;
import com.superprogrammer.system.dto.BillingSettingsUpdateRequest;
import com.superprogrammer.system.dto.BillingSettingsVO;
import com.superprogrammer.system.dto.RagMemorySettingsUpdateRequest;
import com.superprogrammer.system.dto.RagMemorySettingsVO;
import com.superprogrammer.system.dto.RagRecallSettingsUpdateRequest;
import com.superprogrammer.system.dto.RagRecallSettingsVO;
import com.superprogrammer.system.dto.WebSearchSettingsUpdateRequest;
import com.superprogrammer.system.dto.WebSearchSettingsVO;
import com.superprogrammer.system.dto.LlmModelDefaultsVO;
import com.superprogrammer.system.dto.LlmModelDefaultsUpdateRequest;
import com.superprogrammer.system.dto.AuthChannelSettingsUpdateRequest;
import com.superprogrammer.system.dto.AuthChannelSettingsVO;
import com.superprogrammer.system.dto.MailTestRequest;
import com.superprogrammer.auth.service.AuthChannelSettingService;
import com.superprogrammer.auth.service.EmailService;
import com.superprogrammer.llm.service.LlmProviderService;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
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
    private final LlmProviderService llmProviderService;
    private final AuthChannelSettingService authChannelSettingService;
    private final EmailService emailService;

    @GetMapping("/auth-channels")
    @RequirePermission("role:manage")
    public ResponseEntity<R<AuthChannelSettingsVO>> getAuthChannels() {
        return ResponseEntity.ok(R.ok(authChannelSettingService.getSettings()));
    }

    /**
     * 邮件通道测试发信（12x）：给指定邮箱发一封测试信，先测通再开开关。
     * <p>走当前配置快照（不要求 enabled=true），失败返 400 提示查配置/日志。</p>
     */
    @PostMapping("/auth-channels/mail-test")
    @RequirePermission("role:manage")
    @AuditLog(module = "system", action = "mail_channel_test", targetType = "setting")
    public ResponseEntity<R<String>> testMailChannel(@Valid @RequestBody MailTestRequest request) {
        boolean ok = emailService.sendTestMail(request.getTo());
        if (!ok) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "测试邮件发送失败：请检查通道类型/服务器/账号/授权码配置（详见服务端日志）");
        }
        return ResponseEntity.ok(R.ok("测试邮件已发送，请查收（含垃圾箱）", null));
    }

    @PutMapping("/auth-channels")
    @RequirePermission("role:manage")
    @AuditLog(module = "system", action = "update_auth_channels", targetType = "setting")
    public ResponseEntity<R<AuthChannelSettingsVO>> updateAuthChannels(
            @Valid @RequestBody AuthChannelSettingsUpdateRequest request) {
        return ResponseEntity.ok(R.ok("认证通道配置已更新", authChannelSettingService.update(request)));
    }

    @GetMapping("/llm-model-defaults")
    @RequirePermission("llm:config")
    public ResponseEntity<R<LlmModelDefaultsVO>> getLlmModelDefaults() {
        return ResponseEntity.ok(R.ok(buildLlmModelDefaults()));
    }

    @PutMapping("/llm-model-defaults")
    @RequirePermission("llm:config")
    @AuditLog(module = "system", action = "update_llm_model_defaults", targetType = "setting")
    public ResponseEntity<R<LlmModelDefaultsVO>> updateLlmModelDefaults(
            @RequestBody LlmModelDefaultsUpdateRequest request) {
        validateDefaultModel(request.getChatModel(), LlmProviderService.CATEGORY_CHAT, "对话");
        validateDefaultModel(request.getEmbeddingModel(), LlmProviderService.CATEGORY_EMBEDDING, "向量");
        service.updateDefaultModels(request.getChatModel(), request.getEmbeddingModel());
        return ResponseEntity.ok(R.ok("默认模型已更新", buildLlmModelDefaults()));
    }

    private void validateDefaultModel(String model, String category, String label) {
        if (model == null || model.isBlank()) return;
        if (!llmProviderService.listActiveModels(category).contains(model.trim())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    label + "默认模型不属于当前启用的 " + category + " 供应商: " + model);
        }
    }

    private LlmModelDefaultsVO buildLlmModelDefaults() {
        return LlmModelDefaultsVO.builder()
                .chatModel(service.getDefaultChatModel())
                .embeddingModel(service.getDefaultEmbeddingModel())
                .build();
    }

    @GetMapping("/auth")
    @RequirePermission("role:manage")
    public ResponseEntity<R<AuthSettingsVO>> getAuthSettings() {
        return ResponseEntity.ok(R.ok(service.getAuthSettings()));
    }

    @PutMapping("/auth")
    @RequirePermission("role:manage")
    @AuditLog(module = "system", action = "update_auth_settings", targetType = "setting")
    public ResponseEntity<R<AuthSettingsVO>> updateAuthSettings(
            @Valid @RequestBody AuthSettingsUpdateRequest request) {
        return ResponseEntity.ok(R.ok(service.updateAuthSettings(request.getAccessTokenExpirationMs(), request.getSingleSessionEnabled())));
    }

    // ---- 计费设置（安全体系 S2 · L7 低余额并行闸门，SEC-FR-126）----

    @GetMapping("/billing")
    @RequirePermission("role:manage")
    public ResponseEntity<R<BillingSettingsVO>> getBillingSettings() {
        return ResponseEntity.ok(R.ok(service.getBillingSettings()));
    }

    @PutMapping("/billing")
    @RequirePermission("role:manage")
    @AuditLog(module = "system", action = "update_billing_settings", targetType = "setting")
    public ResponseEntity<R<BillingSettingsVO>> updateBillingSettings(
            @Valid @RequestBody BillingSettingsUpdateRequest request) {
        return ResponseEntity.ok(R.ok(service.updateBillingSettings(
                request.getLowBalanceThreshold(), request.getLowBalanceMaxInflight())));
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
    @AuditLog(module = "system", action = "update_rag_memory_settings", targetType = "setting")
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
    @AuditLog(module = "system", action = "update_rag_recall_settings", targetType = "setting")
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
    @AuditLog(module = "system", action = "update_web_search_settings", targetType = "setting")
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
