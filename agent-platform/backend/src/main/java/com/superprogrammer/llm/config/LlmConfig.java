package com.superprogrammer.llm.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.llm.entity.LlmProviderEntity;
import com.superprogrammer.llm.provider.ClaudeProvider;
import com.superprogrammer.llm.provider.LlmProviderInterface;
import com.superprogrammer.llm.provider.OpenAICompatibleProvider;
import com.superprogrammer.llm.service.LlmProviderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.core.type.TypeReference;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class LlmConfig {

    private final LlmProviderService providerService;
    private final ObjectMapper objectMapper;
    private volatile List<LlmProviderInterface> staticProviders = Collections.emptyList();

    @PostConstruct
    public synchronized void initProviders() {
        List<LlmProviderInterface> providers = new ArrayList<>();
        List<LlmProviderEntity> activeProviders = providerService.listActive();
        for (LlmProviderEntity entity : activeProviders) {
            // VIDEO/IMAGE（任务型 provider）不注册为 chat provider——
            // 视频走 ArkSeedanceProvider 等专门任务型 provider 按 category 单独取；IMAGE 为生图预留位。
            if (LlmProviderService.CATEGORY_VIDEO.equalsIgnoreCase(entity.getCategory())
                    || LlmProviderService.CATEGORY_IMAGE.equalsIgnoreCase(entity.getCategory())) {
                log.info("跳过 {} provider（不注册为 chat）: {} ({})",
                        entity.getCategory(), entity.getName(), entity.getApiEndpoint());
                continue;
            }
            String apiKey = providerService.getDecryptedApiKey(entity.getId());
            LlmProviderInterface provider = createProvider(entity, apiKey);
            if (provider != null) {
                providers.add(provider);
                log.info("注册LLM Provider: {} ({})", entity.getName(), entity.getApiEndpoint());
            }
        }
        staticProviders = Collections.unmodifiableList(providers);
        log.info("共注册 {} 个LLM Provider", providers.size());
    }

    public synchronized void reload() {
        log.info("重新加载LLM Provider配置...");
        initProviders();
    }

    public List<LlmProviderInterface> getProviders() {
        return staticProviders;
    }

    public LlmProviderInterface createProvider(LlmProviderEntity entity, String apiKey) {
        String name = entity.getName();
        String baseUrl = entity.getApiEndpoint();
        if (baseUrl == null || baseUrl.isBlank()) return null;

        List<String> models = parseModels(entity.getModels());

        return switch (resolveProtocol(entity)) {
            case "ANTHROPIC" -> new ClaudeProvider(name, baseUrl, apiKey != null ? apiKey : "", models, objectMapper);
            default -> new OpenAICompatibleProvider(name, baseUrl, apiKey != null ? apiKey : "", models, objectMapper);
        };
    }

    private String resolveProtocol(LlmProviderEntity entity) {
        String protocol = entity.getProtocol();
        if (protocol != null && !protocol.isBlank()) {
            return protocol.trim().toUpperCase();
        }
        return "claude".equals(entity.getName()) ? "ANTHROPIC" : "OPENAI_COMPATIBLE";
    }

    private List<String> parseModels(String modelsJson) {
        if (modelsJson == null || modelsJson.isBlank()) return Collections.emptyList();
        try {
            return objectMapper.readValue(modelsJson, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("解析models字段失败: {}", modelsJson);
            return Collections.emptyList();
        }
    }
}
