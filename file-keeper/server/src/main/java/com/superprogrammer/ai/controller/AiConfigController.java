package com.superprogrammer.ai.controller;

import com.superprogrammer.ai.dto.AiConfigCreateRequest;
import com.superprogrammer.ai.dto.AiConfigTestRequest;
import com.superprogrammer.ai.dto.AiConfigUpdateRequest;
import com.superprogrammer.ai.dto.AiConfigVO;
import com.superprogrammer.ai.service.AiConfigService;
import com.superprogrammer.common.BusinessException;
import com.superprogrammer.common.ErrorCode;
import com.superprogrammer.common.R;
import com.superprogrammer.device.service.DeviceBindingService;
import com.superprogrammer.security.AuthPrincipal;
import com.superprogrammer.workreport.service.AiSummaryService;
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

    private final DeviceBindingService deviceBindingService;
    private final AiConfigService aiConfigService;
    private final AiSummaryService aiSummaryService;

    private AuthPrincipal requireActiveDevice(Authentication auth, String deviceId) {
        AuthPrincipal principal = (AuthPrincipal) auth.getPrincipal();
        deviceBindingService.requireActiveDevice(principal.userId(), deviceId);
        return principal;
    }

    @GetMapping
    public R<List<AiConfigVO>> list(Authentication auth, @RequestParam String deviceId) {
        AuthPrincipal principal = requireActiveDevice(auth, deviceId);
        return R.ok(aiConfigService.listByUserId(principal.userId()));
    }

    @GetMapping("/{id}")
    public R<AiConfigVO> get(Authentication auth, @PathVariable Long id, @RequestParam String deviceId) {
        AuthPrincipal principal = requireActiveDevice(auth, deviceId);
        return R.ok(aiConfigService.getByIdAndUserId(id, principal.userId()));
    }

    @PostMapping
    public R<AiConfigVO> create(
            Authentication auth,
            @RequestParam String deviceId,
            @RequestBody @Valid AiConfigCreateRequest request) {
        AuthPrincipal principal = requireActiveDevice(auth, deviceId);
        return R.ok(aiConfigService.create(principal.userId(), request));
    }

    @PutMapping("/{id}")
    public R<AiConfigVO> update(
            Authentication auth,
            @PathVariable Long id,
            @RequestParam String deviceId,
            @RequestBody @Valid AiConfigUpdateRequest request) {
        AuthPrincipal principal = requireActiveDevice(auth, deviceId);
        return R.ok(aiConfigService.update(principal.userId(), id, request));
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(Authentication auth, @PathVariable Long id, @RequestParam String deviceId) {
        AuthPrincipal principal = requireActiveDevice(auth, deviceId);
        aiConfigService.delete(principal.userId(), id);
        return R.ok();
    }

    @PutMapping("/{id}/default")
    public R<AiConfigVO> setDefault(Authentication auth, @PathVariable Long id, @RequestParam String deviceId) {
        AuthPrincipal principal = requireActiveDevice(auth, deviceId);
        return R.ok(aiConfigService.setDefault(principal.userId(), id));
    }

    @PostMapping("/test")
    public R<String> test(
            Authentication auth,
            @RequestParam String deviceId,
            @RequestBody @Valid AiConfigTestRequest request) {
        AuthPrincipal principal = requireActiveDevice(auth, deviceId);
        AiConfigVO testConfig = new AiConfigVO(
                null,
                null,
                request.provider(),
                request.model(),
                request.endpoint(),
                request.maxTokens(),
                request.timeoutSeconds(),
                false,
                true
        );
        try {
            String reply = aiSummaryService.testConnection(testConfig, request.apiKey());
            return R.ok(reply);
        } catch (BusinessException e) {
            return R.fail(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("AI 配置测试连接失败, userId={}, provider={}", principal.userId(), request.provider(), e);
            return R.fail(ErrorCode.INTERNAL_ERROR.getCode(), "AI 连接测试失败: " + e.getMessage());
        }
    }
}
