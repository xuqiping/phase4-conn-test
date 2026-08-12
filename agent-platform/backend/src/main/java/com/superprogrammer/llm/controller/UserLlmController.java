package com.superprogrammer.llm.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.common.result.R;
import com.superprogrammer.llm.dto.AvailableModelVO;
import com.superprogrammer.llm.dto.TestConnectionResult;
import com.superprogrammer.llm.dto.UserLlmProviderRequest;
import com.superprogrammer.llm.dto.UserLlmProviderVO;
import com.superprogrammer.llm.entity.LlmProviderEntity;
import com.superprogrammer.llm.entity.UserLlmProviderEntity;
import com.superprogrammer.llm.service.LlmProviderService;
import com.superprogrammer.llm.service.UserLlmProviderService;
import com.superprogrammer.system.service.SystemSettingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/llm/user")
@RequiredArgsConstructor
public class UserLlmController {

    private final UserLlmProviderService userLlmProviderService;
    private final LlmProviderService llmProviderService;
    private final ObjectMapper objectMapper;
    private final SystemSettingService systemSettingService;

    @GetMapping("/providers")
    public ResponseEntity<R<List<UserLlmProviderVO>>> listProviders() {
        Long userId = getCurrentUserId();
        return ResponseEntity.ok(R.ok(userLlmProviderService.listByUser(userId)));
    }

    @PostMapping("/providers")
    public ResponseEntity<R<UserLlmProviderVO>> createProvider(
            @Valid @RequestBody UserLlmProviderRequest request) {
        Long userId = getCurrentUserId();
        UserLlmProviderEntity entity = new UserLlmProviderEntity();
        entity.setProviderName(request.getProviderName());
        entity.setApiEndpoint(request.getApiEndpoint());
        entity.setApiKeyEnc(request.getApiKey());
        entity.setModels(request.getModels());
        userLlmProviderService.createOrUpdate(userId, entity);
        return ResponseEntity.ok(R.ok(userLlmProviderService.listByUser(userId).stream()
                .filter(v -> v.getProviderName().equals(request.getProviderName()))
                .findFirst().orElse(null)));
    }

    @DeleteMapping("/providers/{id}")
    public ResponseEntity<R<Void>> deleteProvider(@PathVariable Long id) {
        Long userId = getCurrentUserId();
        userLlmProviderService.delete(userId, id);
        return ResponseEntity.ok(R.ok());
    }

    @PostMapping("/providers/{id}/test")
    public ResponseEntity<R<TestConnectionResult>> testConnection(@PathVariable Long id) {
        Long userId = getCurrentUserId();
        UserLlmProviderEntity userEntity = userLlmProviderService.findByUserAndProviderName(
                userId, null); // not useful here, need by id
        // Get user provider by id with ownership check
        String apiKey = userLlmProviderService.getDecryptedApiKey(userId, id);
        if (apiKey == null) {
            return ResponseEntity.ok(R.ok(TestConnectionResult.fail("未配置API Key")));
        }

        // Find the user provider to get providerName
        UserLlmProviderVO userProvider = userLlmProviderService.listByUser(userId).stream()
                .filter(v -> v.getId().equals(id))
                .findFirst().orElse(null);
        if (userProvider == null) {
            return ResponseEntity.ok(R.ok(TestConnectionResult.fail("供应商配置不存在")));
        }

        // Merge: user endpoint overrides global, use global for rest
        LlmProviderEntity globalEntity = llmProviderService.getByName(userProvider.getProviderName());
        if (globalEntity == null) {
            return ResponseEntity.ok(R.ok(TestConnectionResult.fail("全局供应商不存在: " + userProvider.getProviderName())));
        }

        // Apply user overrides
        if (userProvider.getApiEndpoint() != null && !userProvider.getApiEndpoint().isBlank()) {
            globalEntity.setApiEndpoint(userProvider.getApiEndpoint());
        }
        if (userProvider.getModels() != null && !userProvider.getModels().isBlank()) {
            globalEntity.setModels(userProvider.getModels());
        }

        TestConnectionResult result = llmProviderService.testConnection(globalEntity, apiKey);
        return ResponseEntity.ok(R.ok(result));
    }

    @GetMapping("/models/available")
    public ResponseEntity<R<List<AvailableModelVO>>> listAvailableModels() {
        Long userId = getCurrentUserId();
        List<AvailableModelVO> models = new ArrayList<>();
        String defaultModel = systemSettingService.getDefaultChatModel();

        // Global providers（仅 CHAT 进 chat 模型列表；EMBEDDING/VIDEO/IMAGE 均不进——
        // 顺带修掉 EMBEDDING 模型混进 chat 选择器的旧缺陷，FR-003）
        for (LlmProviderEntity p : llmProviderService.listActive()) {
            if (!LlmProviderService.CATEGORY_CHAT.equalsIgnoreCase(p.getCategory())) {
                continue;
            }
            if (p.getModels() != null && !p.getModels().isBlank()) {
                try {
                    List<String> modelList = objectMapper.readValue(p.getModels(), List.class);
                    for (Object m : modelList) {
                        models.add(AvailableModelVO.builder()
                                .modelId(m.toString())
                                .displayName(m.toString())
                                .providerName(p.getName())
                                .source("global")
                                .defaultModel(m.toString().equals(defaultModel))
                                .build());
                    }
                } catch (Exception ignored) {}
            }
        }

        // User provider overrides
        for (UserLlmProviderVO up : userLlmProviderService.listByUser(userId)) {
            if (up.getModels() != null && !up.getModels().isBlank()) {
                try {
                    List<String> modelList = objectMapper.readValue(up.getModels(), List.class);
                    for (Object m : modelList) {
                        models.add(AvailableModelVO.builder()
                                .modelId(m.toString())
                                .displayName(m.toString() + " (我的)")
                                .providerName(up.getProviderName())
                                .source("user")
                                .defaultModel(false)
                                .build());
                    }
                } catch (Exception ignored) {}
            }
        }

        return ResponseEntity.ok(R.ok(models));
    }

    /**
     * C5/D2：视频模型列表（仅 CATEGORY_VIDEO 全局 provider，如 Seedance）。
     * 与 /models/available 互补——后者排除 VIDEO（供 chat 文本/脚本节点），此处只收 VIDEO 供视频节点。
     * 用户私有 provider 的 VIDEO 覆盖暂不纳入（MVP：视频生成走全局 VIDEO provider）。
     * 注：khfz2 V63 把旧 CATEGORY_MEDIA 拆四分为 CHAT/VIDEO/IMAGE/EMBEDDING，视频即 VIDEO。
     */
    @GetMapping("/models/video")
    public ResponseEntity<R<List<AvailableModelVO>>> listVideoModels() {
        List<AvailableModelVO> models = new ArrayList<>();
        for (LlmProviderEntity p : llmProviderService.listActive()) {
            if (!LlmProviderService.CATEGORY_VIDEO.equalsIgnoreCase(p.getCategory())) {
                continue;
            }
            if (p.getModels() != null && !p.getModels().isBlank()) {
                try {
                    List<String> modelList = objectMapper.readValue(p.getModels(), List.class);
                    for (Object m : modelList) {
                        models.add(AvailableModelVO.builder()
                                .modelId(m.toString())
                                .displayName(m.toString())
                                .providerName(p.getName())
                                .source("global")
                                .defaultModel(false)
                                .build());
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return ResponseEntity.ok(R.ok(models));
    }

    private Long getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return (Long) auth.getPrincipal();
    }
}
