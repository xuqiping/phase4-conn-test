package com.superprogrammer.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.billing.entity.LlmUsageLogEntity;
import com.superprogrammer.billing.service.LlmBillingService;
import com.superprogrammer.billing.service.PointsWalletService;
import com.superprogrammer.chat.dto.StreamEvent;
import com.superprogrammer.llm.config.LlmConfig;
import com.superprogrammer.llm.dto.EmbedResult;
import com.superprogrammer.llm.dto.LlmMessage;
import com.superprogrammer.llm.dto.LlmRequest;
import com.superprogrammer.llm.dto.LlmResponse;
import com.superprogrammer.llm.dto.TokenUsage;
import com.superprogrammer.llm.entity.LlmProviderEntity;
import com.superprogrammer.llm.entity.UserLlmProviderEntity;
import com.superprogrammer.llm.provider.ClaudeProvider;
import com.superprogrammer.llm.provider.LlmProviderInterface;
import com.superprogrammer.llm.provider.OpenAICompatibleProvider;
import com.superprogrammer.llm.service.LlmProviderService;
import com.superprogrammer.llm.service.UserLlmProviderService;
import com.superprogrammer.knowledge.util.TokenEstimator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

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
    /** 计费编排（算价→折算→扣→采，全链吞异常不回归出口）。 */
    private final LlmBillingService billingService;
    /** 钱包：入口预检 requireAffordable（≤0 抛 INSUFFICIENT_POINTS）。 */
    private final PointsWalletService walletService;

    public LlmResponse chat(LlmRequest request) {
        // 无 userId（系统调用）→ userId=null：仅采不扣（charge 在 userId=null 时短路）。
        return chat(request, null);
    }

    public LlmResponse chat(LlmRequest request, Long userId) {
        LlmProviderInterface provider = findProvider(request.getModel(), userId);
        log.info("LLM调用 model={} provider={} userId={}", request.getModel(), provider.getName(), userId);
        // 入口预检：余额≤0 抛 INSUFFICIENT_POINTS（disabled/系统调用自短路）。在 try 外，未调用不记 FAILED。
        walletService.requireAffordable(userId);
        try {
            LlmResponse response = provider.chat(request);
            TokenUsage usage = response.getUsage();
            Integer in = usage != null ? usage.getPromptTokens() : null;
            Integer out = usage != null ? usage.getCompletionTokens() : null;
            String status = LlmUsageLogEntity.STATUS_SUCCESS;
            // 估算兜底：parseResponse 理论总带 usage，防御 provider 不回时用 message 内容估算 input
            if ((in == null || in == 0) && (out == null || out == 0)) {
                in = estimateInputTokens(request);
                out = 0;
                status = LlmUsageLogEntity.STATUS_ESTIMATED;
            }
            billingService.onSuccess(userId, provider.getId(), provider.getProviderScope(),
                    request.getModel(), LlmUsageLogEntity.KIND_CHAT, in, out, status);
            return response;
        } catch (RuntimeException e) {
            billingService.onFailure(userId, provider.getId(), provider.getProviderScope(),
                    request.getModel(), LlmUsageLogEntity.KIND_CHAT, e.getMessage());
            throw e;
        }
    }

    public Flux<StreamEvent> chatStream(LlmRequest request) {
        // 无 userId（系统调用）→ userId=null：仅采不扣。
        return chatStream(request, null);
    }

    /**
     * 流式出口计费（Step9/10 side-channel + Step12 接线）：
     * <ul>
     *   <li>入口预检 requireAffordable（≤0 拦）；</li>
     *   <li>usage 经 provider 的 side-channel sink 在 {@code doOnComplete} 回灌 → 在此采+扣；</li>
     *   <li>{@code publishOn(boundedElastic)} 把完成信号切到阻塞容忍调度器，扣减 DB 同步事务不阻塞 netty
     *       event-loop（不改发出的 StreamEvent 序列，仅线程切换，SSE 字节不变）；</li>
     *   <li>provider 不回 usage（sink 不触发）→ 不采不扣（流式无可靠 output，宁少收不误收）。</li>
     * </ul>
     */
    public Flux<StreamEvent> chatStream(LlmRequest request, Long userId) {
        LlmProviderInterface provider = findProvider(request.getModel(), userId);
        log.info("LLM流式调用 model={} provider={} userId={}", request.getModel(), provider.getName(), userId);
        walletService.requireAffordable(userId);
        Long providerId = provider.getId();
        String providerScope = provider.getProviderScope();
        String model = request.getModel();
        return provider.chatStream(request, usage -> billingService.onSuccess(userId, providerId, providerScope,
                model, LlmUsageLogEntity.KIND_CHAT,
                usage.getPromptTokens(), usage.getCompletionTokens()))
                .publishOn(Schedulers.boundedElastic());
    }

    public float[] embed(String text, String model) {
        return embed(text, model, null);
    }

    /**
     * embed 出口计费（Step11 embedWithUsage + Step12 接线）：
     * 入口预检 → provider.embedWithUsage → usage 非空记 SUCCESS，否则 TokenEstimator 估算 input 记 ESTIMATED。
     * <p>embed 路由仍在全局 EMBEDDING 行找（FR-003，不吃用户级 override）；userId 仅透给计费。
     */
    public float[] embed(String text, String model, Long userId) {
        LlmProviderInterface provider = findEmbedProvider(model);
        log.info("embedding 调用 model={} provider={} userId={}", model, provider.getName(), userId);
        walletService.requireAffordable(userId);
        try {
            EmbedResult res = provider.embedWithUsage(text, model);
            TokenUsage usage = res.getUsage();
            Integer in;
            Integer out;
            String status;
            if (usage != null) {
                in = usage.getPromptTokens();
                out = usage.getCompletionTokens();
                status = LlmUsageLogEntity.STATUS_SUCCESS;
            } else {
                // 估算兜底（chars/4）：embed 多无 output，input 用文本估算
                in = TokenEstimator.estimate(text);
                out = 0;
                status = LlmUsageLogEntity.STATUS_ESTIMATED;
            }
            billingService.onSuccess(userId, provider.getId(), provider.getProviderScope(),
                    model, LlmUsageLogEntity.KIND_EMBED, in, out, status);
            return res.getEmbedding();
        } catch (RuntimeException e) {
            billingService.onFailure(userId, provider.getId(), provider.getProviderScope(),
                    model, LlmUsageLogEntity.KIND_EMBED, e.getMessage());
            throw e;
        }
    }

    /** 估算请求 input token（chars/4 启发式，求和各 message content）。仅 usage 缺失兜底用。 */
    private int estimateInputTokens(LlmRequest request) {
        if (request == null || request.getMessages() == null) {
            return 0;
        }
        int sum = 0;
        for (LlmMessage m : request.getMessages()) {
            if (m.getContent() != null) {
                sum += TokenEstimator.estimate(m.getContent());
            }
        }
        return sum;
    }

    /** embed 路由：只在 EMBEDDING 行注册表里按 model 找，找不到报「向量 Provider」话术（与 chat 区分）。 */
    private LlmProviderInterface findEmbedProvider(String model) {
        for (LlmProviderInterface provider : llmConfig.getEmbedProviders()) {
            if (provider.supports(model)) {
                return provider;
            }
        }
        throw new RuntimeException("没有找到支持模型 '" + model + "' 的向量 Provider");
    }

    /**
     * chat 路由：用户级 override（CHAT-only）优先，回落全局 CHAT 注册表。
     * EMBEDDING/VIDEO/IMAGE 行不在此处注册，故 chat 永远找不到它们（FR-003）。
     */
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
                LlmProviderInterface provider = createProviderInstance(up.getProviderName(), protocol, endpoint, apiKey, models, up.getId());
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

        throw new RuntimeException("没有找到支持模型 '" + model + "' 的对话 Provider");
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

    private LlmProviderInterface createProviderInstance(String name, String protocol, String baseUrl, String apiKey,
                                                         List<String> models, Long userProviderId) {
        if (baseUrl == null || baseUrl.isBlank()) return null;
        // 安全审计 #3：用户自填 endpoint SSRF 防护。单一咽喉点——所有用户级 provider 实例化必经此处。
        com.superprogrammer.common.security.SsrfGuard.validate(baseUrl);
        // 用户级 override：scope=USER，id=user_llm_providers.id（独立命名空间，靠 scope 区分于全局 llm_providers.id）
        return switch (resolveProtocol(name, protocol)) {
            case "ANTHROPIC" -> new ClaudeProvider(name, baseUrl, apiKey != null ? apiKey : "", models, objectMapper,
                    userProviderId, "USER");
            default -> new OpenAICompatibleProvider(name, baseUrl, apiKey != null ? apiKey : "", models, objectMapper,
                    userProviderId, "USER");
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
