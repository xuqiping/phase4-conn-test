package com.superprogrammer.llm.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.common.audit.AuditLog;
import com.superprogrammer.common.result.R;
import com.superprogrammer.llm.dto.LlmProviderCreateRequest;
import com.superprogrammer.llm.dto.LlmProviderExportItem;
import com.superprogrammer.llm.dto.LlmProviderVO;
import com.superprogrammer.llm.dto.ProviderExportRequest;
import com.superprogrammer.llm.dto.ProviderImportResult;
import com.superprogrammer.llm.dto.TestConnectionResult;
import com.superprogrammer.llm.entity.LlmProviderEntity;
import com.superprogrammer.llm.service.LlmProviderService;
import com.superprogrammer.auth.security.RequirePermission;
import com.superprogrammer.auth.service.AuthService;
import com.superprogrammer.llm.config.LlmConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/llm")
@RequiredArgsConstructor
public class LlmController {

    private final LlmProviderService providerService;
    private final LlmConfig llmConfig;
    private final ObjectMapper objectMapper;
    /** 修复VIII B4：导出密码二次确认（复用注销同款校验）。 */
    private final AuthService authService;

    @GetMapping("/providers")
    @RequirePermission("llm:config")
    public ResponseEntity<R<List<LlmProviderVO>>> listProviders() {
        return ResponseEntity.ok(R.ok(providerService.listAll()));
    }

    /** 按能力返回当前启用模型，供业务页面下拉选择；不暴露供应商密钥。 */
    @GetMapping("/models/active")
    public ResponseEntity<R<List<String>>> listActiveModels(@RequestParam String category) {
        return ResponseEntity.ok(R.ok(providerService.listActiveModels(category)));
    }

    @PostMapping("/providers")
    @RequirePermission("llm:config")
    @AuditLog(module = "llm", action = "provider_create", targetType = "llm_provider")
    public ResponseEntity<R<LlmProviderVO>> createProvider(
            @Valid @RequestBody LlmProviderCreateRequest request) {
        LlmProviderEntity entity = toEntity(request);
        providerService.create(entity);
        Long createdId = entity.getId();
        return ResponseEntity.ok(R.ok(providerService.listAll().stream()
                .filter(v -> v.getId().equals(createdId))
                .findFirst().orElse(null)));
    }

    @PutMapping("/providers/{id}")
    @RequirePermission("llm:config")
    @AuditLog(module = "llm", action = "provider_update", targetType = "llm_provider")
    public ResponseEntity<R<LlmProviderVO>> updateProvider(
            @PathVariable Long id,
            @Valid @RequestBody LlmProviderCreateRequest request) {
        LlmProviderEntity entity = toEntity(request);
        providerService.update(id, entity);
        return ResponseEntity.ok(R.ok(providerService.listAll().stream()
                .filter(v -> v.getId().equals(id))
                .findFirst().orElse(null)));
    }

    @DeleteMapping("/providers/{id}")
    @RequirePermission("llm:config")
    @AuditLog(module = "llm", action = "provider_delete", targetType = "llm_provider")
    public ResponseEntity<R<Void>> deleteProvider(@PathVariable Long id) {
        providerService.delete(id);
        return ResponseEntity.ok(R.ok());
    }

    @PostMapping("/providers/{id}/test")
    @RequirePermission("llm:config")
    @AuditLog(module = "llm", action = "provider_test", targetType = "llm_provider")
    public ResponseEntity<R<TestConnectionResult>> testConnection(@PathVariable Long id) {
        TestConnectionResult result = providerService.testConnection(id);
        return ResponseEntity.ok(R.ok(result));
    }

    @PostMapping("/providers/{id}/test-embed")
    @RequirePermission("llm:config")
    @AuditLog(module = "llm", action = "provider_test", targetType = "llm_provider")
    public ResponseEntity<R<TestConnectionResult>> testEmbedding(@PathVariable Long id) {
        TestConnectionResult result = providerService.testEmbedding(id);
        return ResponseEntity.ok(R.ok(result));
    }

    @PostMapping("/providers/{id}/test-rerank")
    @RequirePermission("llm:config")
    @AuditLog(module = "llm", action = "provider_test", targetType = "llm_provider")
    public ResponseEntity<R<TestConnectionResult>> testRerank(@PathVariable Long id) {
        return ResponseEntity.ok(R.ok(providerService.testRerank(id)));
    }

    @PostMapping("/providers/reload")
    @RequirePermission("llm:config")
    @AuditLog(module = "llm", action = "provider_reload", targetType = "llm_provider")
    public ResponseEntity<R<Void>> reloadProviders() {
        llmConfig.reload();
        return ResponseEntity.ok(R.ok());
    }

    /**
     * 导出全量供应商为 JSON 文件下载（问题 10x-2；修复VIII B4/VIII-5 改 POST + 密码二次确认）。
     * <p>持 llm:config 可调（16x 起独立码，admin 不再天然持有）；导出文件含<b>明文 API Key</b>，
     * 故 body 必须携带当前登录密码复验（复用注销同款 {@code AuthService.verifyUserPassword}，
     * BCrypt matches）——密码绝不进 URL query（nginx 日志面），旧 GET 端点直接删除（同仓同发版）。
     * 密码错/缺失 → BusinessException(BAD_REQUEST)，@AuditLog 的 @Around 切面对失败尝试同样落 FAIL 行；
     * 响应头 Content-Disposition 触发浏览器下载。
     */
    @PostMapping("/providers/export")
    @RequirePermission("llm:config")
    @AuditLog(module = "llm", action = "provider_export", targetType = "llm_provider")
    public ResponseEntity<byte[]> exportProviders(@RequestBody ProviderExportRequest request) {
        authService.verifyUserPassword(currentUserId(), request.getPassword());
        var items = providerService.exportAll();
        byte[] body;
        try {
            body = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(items);
        } catch (Exception e) {
            log.error("导出供应商序列化失败", e);
            throw new com.superprogrammer.common.exception.BusinessException(
                    com.superprogrammer.common.exception.ErrorCode.INTERNAL_ERROR, "导出失败");
        }
        String filename = "llm-providers-" + java.time.LocalDate.now() + ".json";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    /**
     * 批量导入供应商（问题 10x-2）：按 name upsert，返回 created/updated/failed 统计。
     * <p>持 llm:config 可调；非法行不中断整体导入；导入后自动 reload 让配置即时生效。
     */
    @PostMapping("/providers/import")
    @RequirePermission("llm:config")
    @AuditLog(module = "llm", action = "provider_import", targetType = "llm_provider")
    public ResponseEntity<R<ProviderImportResult>> importProviders(
            @RequestBody List<LlmProviderExportItem> items) {
        return ResponseEntity.ok(R.ok(providerService.importAll(items)));
    }

    /** 修复VIII B4：当前登录用户 id（JwtAuthenticationFilter 已把 principal 设为 Long userId）。 */
    private Long currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return (Long) authentication.getPrincipal();
    }

    private LlmProviderEntity toEntity(LlmProviderCreateRequest request) {
        LlmProviderEntity entity = new LlmProviderEntity();
        entity.setName(request.getName());
        entity.setDisplayName(request.getDisplayName());
        entity.setProtocol(request.getProtocol());
        entity.setApiEndpoint(request.getApiEndpoint());
        entity.setApiKeyEnc(request.getApiKey());
        entity.setModels(request.getModels());
        entity.setConfig(request.getConfig());
        entity.setSortOrder(request.getSortOrder());
        entity.setCategory(request.getCategory());
        entity.setStatus("ACTIVE");
        return entity;
    }
}
