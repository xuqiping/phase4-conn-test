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
    /** CHAT 行注册表——chat/chatStream 路由只在这里找（FR-002/FR-003）。 */
    private volatile List<LlmProviderInterface> staticProviders = Collections.emptyList();
    /** EMBEDDING 行注册表——embed 路由只在这里找，不进 chat 路由/模型列表。 */
    private volatile List<LlmProviderInterface> staticEmbedProviders = Collections.emptyList();
    /** RERANK 行注册表——专用重排只在这里找，绝不进入 Chat。 */
    private volatile List<LlmProviderInterface> staticRerankProviders = Collections.emptyList();

    @PostConstruct
    public synchronized void initProviders() {
        List<LlmProviderInterface> providers = new ArrayList<>();
        List<LlmProviderInterface> embedProviders = new ArrayList<>();
        List<LlmProviderInterface> rerankProviders = new ArrayList<>();
        List<LlmProviderEntity> activeProviders = providerService.listActive();
        for (LlmProviderEntity entity : activeProviders) {
            // VIDEO/IMAGE（任务型 provider）不注册——
            // 视频走 ArkSeedanceProvider 等专门任务型 provider 按 category 单独取；IMAGE 为生图预留位。
            if (LlmProviderService.CATEGORY_VIDEO.equalsIgnoreCase(entity.getCategory())
                    || LlmProviderService.CATEGORY_IMAGE.equalsIgnoreCase(entity.getCategory())) {
                log.info("跳过 {} provider（不注册为 chat）: {} ({})",
                        entity.getCategory(), entity.getName(), entity.getApiEndpoint());
                continue;
            }
            String apiKey = providerService.getDecryptedApiKey(entity.getId());
            LlmProviderInterface provider = createProvider(entity, apiKey);
            if (provider == null) {
                continue;
            }
            // EMBEDDING 行注册进 embed 专用表：仅 embed 路由可达，chat 路由找不到（FR-003 按类型路由）。
            if (LlmProviderService.CATEGORY_EMBEDDING.equalsIgnoreCase(entity.getCategory())) {
                embedProviders.add(provider);
                log.info("注册 embedding Provider（仅 embed，不进 chat 路由）: {} ({})",
                        entity.getName(), entity.getApiEndpoint());
            } else if (LlmProviderService.CATEGORY_RERANK.equalsIgnoreCase(entity.getCategory())) {
                rerankProviders.add(provider);
                log.info("注册 rerank Provider（仅 rerank，不进 chat 路由）: {} ({})",
                        entity.getName(), entity.getApiEndpoint());
            } else {
                providers.add(provider);
                log.info("注册LLM Provider: {} ({})", entity.getName(), entity.getApiEndpoint());
            }
        }
        staticProviders = Collections.unmodifiableList(providers);
        staticEmbedProviders = Collections.unmodifiableList(embedProviders);
        staticRerankProviders = Collections.unmodifiableList(rerankProviders);
        log.info("共注册 {} 个 chat + {} 个 embedding + {} 个 rerank Provider",
                providers.size(), embedProviders.size(), rerankProviders.size());
    }

    public synchronized void reload() {
        log.info("重新加载LLM Provider配置...");
        initProviders();
    }

    /** chat 路由注册表（仅 CHAT 行）。 */
    public List<LlmProviderInterface> getProviders() {
        return staticProviders;
    }

    /** embed 路由注册表（仅 EMBEDDING 行）；embed 调用只在这里找，不回落 chat 表。 */
    public List<LlmProviderInterface> getEmbedProviders() {
        return staticEmbedProviders;
    }

    public List<LlmProviderInterface> getRerankProviders() {
        return staticRerankProviders;
    }

    public LlmProviderInterface createProvider(LlmProviderEntity entity, String apiKey) {
        String name = entity.getName();
        String baseUrl = entity.getApiEndpoint();
        if (baseUrl == null || baseUrl.isBlank()) return null;

        List<String> models = parseModels(entity.getModels());

        return switch (resolveProtocol(entity)) {
            case "ANTHROPIC" -> new ClaudeProvider(name, baseUrl, apiKey != null ? apiKey : "", models, objectMapper,
                    entity.getId(), "GLOBAL");
            default -> new OpenAICompatibleProvider(name, baseUrl, apiKey != null ? apiKey : "", models, objectMapper,
                    entity.getId(), "GLOBAL");
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
