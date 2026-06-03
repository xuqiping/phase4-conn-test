package com.superprogrammer.llm.controller;

import com.superprogrammer.common.result.R;
import com.superprogrammer.llm.dto.LlmProviderCreateRequest;
import com.superprogrammer.llm.dto.LlmProviderVO;
import com.superprogrammer.llm.dto.TestConnectionResult;
import com.superprogrammer.llm.entity.LlmProviderEntity;
import com.superprogrammer.llm.service.LlmProviderService;
import com.superprogrammer.auth.security.RequirePermission;
import com.superprogrammer.llm.config.LlmConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.List;

@RestController
@RequestMapping("/api/llm")
@RequiredArgsConstructor
public class LlmController {

    private final LlmProviderService providerService;
    private final LlmConfig llmConfig;

    @GetMapping("/providers")
    @RequirePermission("role:manage")
    public ResponseEntity<R<List<LlmProviderVO>>> listProviders() {
        return ResponseEntity.ok(R.ok(providerService.listAll()));
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

    @PostMapping("/providers/reload")
    @RequirePermission("role:manage")
    public ResponseEntity<R<Void>> reloadProviders() {
        llmConfig.reload();
        return ResponseEntity.ok(R.ok());
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
        entity.setStatus("ACTIVE");
        return entity;
    }
}
