package com.superprogrammer.system.controller;

import com.superprogrammer.auth.security.RequirePermission;
import com.superprogrammer.common.result.R;
import com.superprogrammer.system.dto.AuthSettingsUpdateRequest;
import com.superprogrammer.system.dto.AuthSettingsVO;
import com.superprogrammer.system.dto.RagMemorySettingsUpdateRequest;
import com.superprogrammer.system.dto.RagMemorySettingsVO;
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
        return ResponseEntity.ok(R.ok(RagMemorySettingsVO.builder().enabled(service.getRagMemoryEnabled()).build()));
    }

    @PutMapping("/rag-memory")
    @RequirePermission("role:manage")
    public ResponseEntity<R<RagMemorySettingsVO>> updateRagMemorySettings(
            @Valid @RequestBody RagMemorySettingsUpdateRequest request) {
        service.updateRagMemoryEnabled(request.getEnabled());
        return ResponseEntity.ok(R.ok("RAG/记忆模式开关已更新",
                RagMemorySettingsVO.builder().enabled(service.getRagMemoryEnabled()).build()));
    }
}
