package com.superprogrammer.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.chat.dto.StreamEvent;
import com.superprogrammer.llm.config.LlmConfig;
import com.superprogrammer.llm.dto.LlmRequest;
import com.superprogrammer.llm.dto.LlmResponse;
import com.superprogrammer.llm.entity.LlmProviderEntity;
import com.superprogrammer.llm.entity.UserLlmProviderEntity;
import com.superprogrammer.llm.provider.ClaudeProvider;
import com.superprogrammer.llm.provider.LlmProviderInterface;
import com.superprogrammer.llm.provider.OpenAICompatibleProvider;
import com.superprogrammer.llm.service.LlmProviderService;
import com.superprogrammer.llm.service.UserLlmProviderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.Collections;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class LlmGateway {

    private final LlmConfig llmConfig;
    private final UserLlmProviderService userLlmProviderService;
    private final LlmProviderService llmProviderService;
    private final ObjectMapper objectMapper;

    public LlmResponse chat(LlmRequest request) {
        LlmProviderInterface provider = findProvider(request.getModel(), null);
        log.info("LLM调用 model={} provider={}", request.getModel(), provider.getName());
        return provider.chat(request);
    }

    public LlmResponse chat(LlmRequest request, Long userId) {
        LlmProviderInterface provider = findProvider(request.getModel(), userId);
        log.info("LLM调用 model={} provider={} userId={}", request.getModel(), provider.getName(), userId);
        return provider.chat(request);
    }

    public Flux<StreamEvent> chatStream(LlmRequest request) {
        LlmProviderInterface provider = findProvider(request.getModel(), null);
        log.info("LLM流式调用 model={} provider={}", request.getModel(), provider.getName());
        return provider.chatStream(request);
    }

    public Flux<StreamEvent> chatStream(LlmRequest request, Long userId) {
        LlmProviderInterface provider = findProvider(request.getModel(), userId);
        log.info("LLM流式调用 model={} provider={} userId={}", request.getModel(), provider.getName(), userId);
        return provider.chatStream(request);
    }

    public float[] embed(String text, String model) {
        LlmProviderInterface provider = findProvider(model, null);
        log.info("embedding 调用 model={} provider={}", model, provider.getName());
        return provider.embed(text, model);
    }

    public float[] embed(String text, String model, Long userId) {
        LlmProviderInterface provider = findProvider(model, userId);
        log.info("embedding 调用 model={} provider={} userId={}", model, provider.getName(), userId);
        return provider.embed(text, model);
    }

    private LlmProviderInterface findProvider(String model, Long userId) {
        // Step 1: Check user provider overrides
        if (userId != null) {
            List<UserLlmProviderEntity> userProviders = getUserProviders(userId);
            for (UserLlmProviderEntity up : userProviders) {
                String apiKey = userLlmProviderService.getDecryptedApiKey(userId, up.getId());
                String endpoint = up.getApiEndpoint();
                LlmProviderEntity globalEntity = llmProviderService.getByName(up.getProviderName());
                List<String> models = parseModels(up.getModels());
                if (models.isEmpty() && globalEntity != null) {
                    models = parseModels(globalEntity.getModels());
                }
                if (endpoint == null || endpoint.isBlank()) {
                    // Inherit from global provider
                    LlmProviderInterface global = findGlobalProvider(up.getProviderName());
                    if (global == null) continue;
                    return global; // use global provider directly
                }
                String protocol = globalEntity != null ? globalEntity.getProtocol() : null;
                LlmProviderInterface provider = createProviderInstance(up.getProviderName(), protocol, endpoint, apiKey, models);
                if (provider != null && provider.supports(model)) {
                    log.debug("使用用户Provider: userId={}, provider={}", userId, up.getProviderName());
                    return provider;
                }
            }
        }

        // Step 2: Fall back to global providers
        for (LlmProviderInterface provider : llmConfig.getProviders()) {
            if (provider.supports(model)) {
                return provider;
            }
        }

        throw new RuntimeException("没有找到支持模型 '" + model + "' 的Provider");
    }

    private List<UserLlmProviderEntity> getUserProviders(Long userId) {
        try {
            return userLlmProviderService.listByUser(userId).stream()
                    .map(vo -> {
                        UserLlmProviderEntity e = new UserLlmProviderEntity();
                        e.setId(vo.getId());
                        e.setProviderName(vo.getProviderName());
                        e.setApiEndpoint(vo.getApiEndpoint());
                        e.setModels(vo.getModels());
                        e.setUserId(userId);
                        return e;
                    })
                    .toList();
        } catch (Exception e) {
            log.warn("获取用户Provider失败: {}", e.getMessage());
            return List.of();
        }
    }

    private LlmProviderInterface findGlobalProvider(String name) {
        return llmConfig.getProviders().stream()
                .filter(p -> p.getName().equals(name))
                .findFirst()
                .orElse(null);
    }

    private LlmProviderInterface createProviderInstance(String name, String protocol, String baseUrl, String apiKey, List<String> models) {
        if (baseUrl == null || baseUrl.isBlank()) return null;
        // 安全审计 #3：用户自填 endpoint SSRF 防护。单一咽喉点——所有用户级 provider 实例化必经此处。
        com.superprogrammer.common.security.SsrfGuard.validate(baseUrl);
        return switch (resolveProtocol(name, protocol)) {
            case "ANTHROPIC" -> new ClaudeProvider(name, baseUrl, apiKey != null ? apiKey : "", models, objectMapper);
            default -> new OpenAICompatibleProvider(name, baseUrl, apiKey != null ? apiKey : "", models, objectMapper);
        };
    }

    private String resolveProtocol(String name, String protocol) {
        if (protocol != null && !protocol.isBlank()) {
            return protocol.trim().toUpperCase();
        }
        return "claude".equals(name) ? "ANTHROPIC" : "OPENAI_COMPATIBLE";
    }

    private List<String> parseModels(String modelsJson) {
        if (modelsJson == null || modelsJson.isBlank()) return Collections.emptyList();
        try {
            return objectMapper.readValue(modelsJson, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }
}
