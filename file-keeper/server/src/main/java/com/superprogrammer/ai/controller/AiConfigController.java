package com.superprogrammer.ai.controller;

import com.superprogrammer.ai.dto.AiConfigCreateRequest;
import com.superprogrammer.ai.dto.AiConfigUpdateRequest;
import com.superprogrammer.ai.dto.AiConfigVO;
import com.superprogrammer.ai.service.AiConfigService;
import com.superprogrammer.authorization.dto.AuthorizationSnapshot;
import com.superprogrammer.authorization.dto.ModuleAccess;
import com.superprogrammer.authorization.service.AuthorizationService;
import com.superprogrammer.common.ErrorCode;
import com.superprogrammer.common.R;
import com.superprogrammer.security.AuthConstants;
import com.superprogrammer.security.AuthPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/client/ai-configs")
@RequiredArgsConstructor
@Slf4j
public class AiConfigController {

    private final AuthorizationService authorizationService;
    private final AiConfigService aiConfigService;

    private ModuleAccess checkAiModuleAuth(Authentication auth, String deviceId) {
        AuthPrincipal principal = (AuthPrincipal) auth.getPrincipal();
        AuthorizationSnapshot snapshot = authorizationService.authenticatedSnapshot(
                principal.userId(), deviceId, System.currentTimeMillis()
        );
        return snapshot.modules().stream()
                .filter(m -> AuthConstants.MODULE_AI.equals(m.moduleCode()))
                .findFirst()
                .orElse(null);
    }

    private <T> R<T> forbidden(ModuleAccess access) {
        String reason = access != null && access.reason() != null ? access.reason() : "未授权访问 AI 能力";
        return R.fail(ErrorCode.FORBIDDEN.getCode(), reason);
    }

    @GetMapping
    public R<List<AiConfigVO>> list(Authentication auth, @RequestParam String deviceId) {
        ModuleAccess access = checkAiModuleAuth(auth, deviceId);
        if (access == null || !access.allowed()) {
            return forbidden(access);
        }
        AuthPrincipal principal = (AuthPrincipal) auth.getPrincipal();
        return R.ok(aiConfigService.listByUserId(principal.userId()));
    }

    @GetMapping("/{id}")
    public R<AiConfigVO> get(Authentication auth, @PathVariable Long id, @RequestParam String deviceId) {
        ModuleAccess access = checkAiModuleAuth(auth, deviceId);
        if (access == null || !access.allowed()) {
            return forbidden(access);
        }
        AuthPrincipal principal = (AuthPrincipal) auth.getPrincipal();
        return R.ok(aiConfigService.getByIdAndUserId(id, principal.userId()));
    }

    @PostMapping
    public R<AiConfigVO> create(
            Authentication auth,
            @RequestParam String deviceId,
            @RequestBody @Valid AiConfigCreateRequest request) {
        ModuleAccess access = checkAiModuleAuth(auth, deviceId);
        if (access == null || !access.allowed()) {
            return forbidden(access);
        }
        AuthPrincipal principal = (AuthPrincipal) auth.getPrincipal();
        return R.ok(aiConfigService.create(principal.userId(), request));
    }

    @PutMapping("/{id}")
    public R<AiConfigVO> update(
            Authentication auth,
            @PathVariable Long id,
            @RequestParam String deviceId,
            @RequestBody @Valid AiConfigUpdateRequest request) {
        ModuleAccess access = checkAiModuleAuth(auth, deviceId);
        if (access == null || !access.allowed()) {
            return forbidden(access);
        }
        AuthPrincipal principal = (AuthPrincipal) auth.getPrincipal();
        return R.ok(aiConfigService.update(principal.userId(), id, request));
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(Authentication auth, @PathVariable Long id, @RequestParam String deviceId) {
        ModuleAccess access = checkAiModuleAuth(auth, deviceId);
        if (access == null || !access.allowed()) {
            return forbidden(access);
        }
        AuthPrincipal principal = (AuthPrincipal) auth.getPrincipal();
        aiConfigService.delete(principal.userId(), id);
        return R.ok();
    }

    @PutMapping("/{id}/default")
    public R<AiConfigVO> setDefault(Authentication auth, @PathVariable Long id, @RequestParam String deviceId) {
        ModuleAccess access = checkAiModuleAuth(auth, deviceId);
        if (access == null || !access.allowed()) {
            return forbidden(access);
        }
        AuthPrincipal principal = (AuthPrincipal) auth.getPrincipal();
        return R.ok(aiConfigService.setDefault(principal.userId(), id));
    }
}
