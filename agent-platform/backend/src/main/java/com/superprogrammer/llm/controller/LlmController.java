package com.superprogrammer.llm.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.common.audit.AuditLog;
import com.superprogrammer.common.result.R;
import com.superprogrammer.llm.dto.LlmProviderCreateRequest;
import com.superprogrammer.llm.dto.LlmProviderExportItem;
import com.superprogrammer.llm.dto.LlmProviderVO;
import com.superprogrammer.llm.dto.ProviderImportResult;
import com.superprogrammer.llm.dto.TestConnectionResult;
import com.superprogrammer.llm.entity.LlmProviderEntity;
import com.superprogrammer.llm.service.LlmProviderService;
import com.superprogrammer.auth.security.RequirePermission;
import com.superprogrammer.llm.config.LlmConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

    @GetMapping("/providers")
    @RequirePermission("role:manage")
    public ResponseEntity<R<List<LlmProviderVO>>> listProviders() {
        return ResponseEntity.ok(R.ok(providerService.listAll()));
    }

    /** 按能力返回当前启用模型，供业务页面下拉选择；不暴露供应商密钥。 */
    @GetMapping("/models/active")
    public ResponseEntity<R<List<String>>> listActiveModels(@RequestParam String category) {
        return ResponseEntity.ok(R.ok(providerService.listActiveModels(category)));
    }

    @PostMapping("/providers")
    @RequirePermission("role:manage")
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
    @RequirePermission("role:manage")
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
    @RequirePermission("role:manage")
    public ResponseEntity<R<Void>> deleteProvider(@PathVariable Long id) {
        providerService.delete(id);
        return ResponseEntity.ok(R.ok());
    }

    @PostMapping("/providers/{id}/test")
    @RequirePermission("role:manage")
    public ResponseEntity<R<TestConnectionResult>> testConnection(@PathVariable Long id) {
        TestConnectionResult result = providerService.testConnection(id);
        return ResponseEntity.ok(R.ok(result));
    }

    @PostMapping("/providers/{id}/test-embed")
    @RequirePermission("role:manage")
    public ResponseEntity<R<TestConnectionResult>> testEmbedding(@PathVariable Long id) {
        TestConnectionResult result = providerService.testEmbedding(id);
        return ResponseEntity.ok(R.ok(result));
    }

    @PostMapping("/providers/reload")
    @RequirePermission("role:manage")
    public ResponseEntity<R<Void>> reloadProviders() {
        llmConfig.reload();
        return ResponseEntity.ok(R.ok());
    }

    /**
     * 导出全量供应商为 JSON 文件下载（问题 10x-2）。
     * <p>仅 admin（@RequirePermission role:manage）；导出文件含<b>明文 API Key</b>，
     * 响应头 Content-Disposition 触发浏览器下载；@AuditLog 留痕（明文 key 外流必须可追溯）。
     */
    @GetMapping("/providers/export")
    @RequirePermission("role:manage")
    @AuditLog(module = "llm", action = "provider_export", targetType = "llm_provider")
    public ResponseEntity<byte[]> exportProviders() {
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
     * <p>仅 admin；非法行不中断整体导入；导入后自动 reload 让配置即时生效。
     */
    @PostMapping("/providers/import")
    @RequirePermission("role:manage")
    @AuditLog(module = "llm", action = "provider_import", targetType = "llm_provider")
    public ResponseEntity<R<ProviderImportResult>> importProviders(
            @RequestBody List<LlmProviderExportItem> items) {
        return ResponseEntity.ok(R.ok(providerService.importAll(items)));
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
