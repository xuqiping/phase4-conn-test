package com.superprogrammer.llm.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.llm.config.LlmConfig;
import com.superprogrammer.llm.dto.LlmProviderVO;
import com.superprogrammer.llm.entity.LlmProviderEntity;
import com.superprogrammer.llm.mapper.LlmProviderMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import com.superprogrammer.llm.dto.LlmMessage;
import com.superprogrammer.llm.dto.LlmRequest;
import com.superprogrammer.llm.dto.LlmResponse;
import com.superprogrammer.llm.dto.TestConnectionResult;
import com.superprogrammer.llm.provider.LlmProviderInterface;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
public class LlmProviderService {

    private static final String DEFAULT_CATEGORY = "CHAT";
    private static final Set<String> CATEGORIES = Set.of("CHAT", "EMBEDDING", "CHAT_EMBEDDING", "MEDIA");
    /** MEDIA = 视频/生图等任务型 provider，不参与 chat 路由/模型列表（视频代码按 name 单独取）。 */
    public static final String CATEGORY_MEDIA = "MEDIA";

    /** 规范化 category：合法原样返回，null/blank/非法 → CHAT（容错 warn 不抛 400）。 */
    private String normalizeCategory(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_CATEGORY;
        }
        String upper = raw.trim().toUpperCase();
        if (!CATEGORIES.contains(upper)) {
            log.warn("非法 category={}, 回退 {}", raw, DEFAULT_CATEGORY);
            return DEFAULT_CATEGORY;
        }
        return upper;
    }

    private final LlmProviderMapper mapper;
    private final AesEncryptService aesEncryptService;
    private final LlmConfig llmConfig;
    private final com.superprogrammer.llm.mapper.EmbeddingModelVersionMapper embeddingModelVersionMapper;

    public LlmProviderService(LlmProviderMapper mapper, AesEncryptService aesEncryptService, @Lazy LlmConfig llmConfig,
                              com.superprogrammer.llm.mapper.EmbeddingModelVersionMapper embeddingModelVersionMapper) {
        this.mapper = mapper;
        this.aesEncryptService = aesEncryptService;
        this.llmConfig = llmConfig;
        this.embeddingModelVersionMapper = embeddingModelVersionMapper;
    }

    public LlmProviderEntity create(LlmProviderEntity entity) {
        if (entity.getApiKeyEnc() != null && !entity.getApiKeyEnc().isBlank()) {
            entity.setApiKeyEnc(aesEncryptService.encrypt(entity.getApiKeyEnc()));
        }
        entity.setCategory(normalizeCategory(entity.getCategory()));
        mapper.insert(entity);
        llmConfig.reload();
        return entity;
    }

    public LlmProviderEntity update(Long id, LlmProviderEntity updates) {
        LlmProviderEntity existing = mapper.selectById(id);
        if (existing == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "供应商不存在");
        }
        if (updates.getName() != null) existing.setName(updates.getName());
        if (updates.getDisplayName() != null) existing.setDisplayName(updates.getDisplayName());
        if (updates.getProtocol() != null) existing.setProtocol(updates.getProtocol());
        if (updates.getApiEndpoint() != null) existing.setApiEndpoint(updates.getApiEndpoint());
        if (updates.getApiKeyEnc() != null && !updates.getApiKeyEnc().isBlank()) {
            existing.setApiKeyEnc(aesEncryptService.encrypt(updates.getApiKeyEnc()));
        }
        if (updates.getModels() != null) existing.setModels(updates.getModels());
        if (updates.getConfig() != null) existing.setConfig(updates.getConfig());
        if (updates.getStatus() != null) existing.setStatus(updates.getStatus());
        if (updates.getSortOrder() != null) existing.setSortOrder(updates.getSortOrder());
        if (updates.getCategory() != null && !updates.getCategory().isBlank()) {
            existing.setCategory(normalizeCategory(updates.getCategory()));
        }
        mapper.updateById(existing);
        llmConfig.reload();
        return existing;
    }

    public List<LlmProviderEntity> listActive() {
        LambdaQueryWrapper<LlmProviderEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(LlmProviderEntity::getStatus, "ACTIVE")
               .eq(LlmProviderEntity::getDeleted, 0)
               .orderByAsc(LlmProviderEntity::getSortOrder);
        return mapper.selectList(wrapper);
    }

    public List<LlmProviderVO> listAll() {
        LambdaQueryWrapper<LlmProviderEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(LlmProviderEntity::getDeleted, 0)
               .orderByAsc(LlmProviderEntity::getSortOrder);
        Integer activeDim = embeddingModelVersionMapper.findActiveDimension();
        return mapper.selectList(wrapper).stream()
                .map(e -> toVO(e, activeDim))
                .collect(Collectors.toList());
    }

    public String getDecryptedApiKey(Long id) {
        LlmProviderEntity entity = mapper.selectById(id);
        if (entity == null || entity.getApiKeyEnc() == null) {
            return null;
        }
        return aesEncryptService.decrypt(entity.getApiKeyEnc());
    }

    public void delete(Long id) {
        LlmProviderEntity entity = mapper.selectById(id);
        if (entity == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "供应商不存在");
        }
        mapper.deleteById(id);
        llmConfig.reload();
    }

    public LlmProviderEntity getByName(String name) {
        LambdaQueryWrapper<LlmProviderEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(LlmProviderEntity::getName, name)
               .eq(LlmProviderEntity::getDeleted, 0);
        return mapper.selectOne(wrapper);
    }

    public LlmProviderEntity getDefaultProvider() {
        List<LlmProviderEntity> active = listActive();
        return active.isEmpty() ? null : active.get(0);
    }

    /**
     * 测试供应商连通性：发送短消息验证 API Key + Endpoint 配置
     */
    public TestConnectionResult testConnection(Long providerId) {
        LlmProviderEntity entity = mapper.selectById(providerId);
        if (entity == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "供应商不存在");
        }
        return doTestConnection(entity, getDecryptedApiKey(providerId));
    }

    /**
     * embedding 专用连通测试：embed("hello") 验证 endpoint/apiKey/model 路由，
     * 返回向量维度。纯 embedding provider 不支持 chat()，须走此路径。
     */
    public TestConnectionResult testEmbedding(Long providerId) {
        LlmProviderEntity entity = mapper.selectById(providerId);
        if (entity == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "供应商不存在");
        }
        String model = pickFirstModel(entity);
        if (model == null) {
            return TestConnectionResult.fail("未配置模型列表");
        }
        if (entity.getApiEndpoint() == null || entity.getApiEndpoint().isBlank()) {
            return TestConnectionResult.fail("未配置API端点");
        }
        try {
            LlmProviderInterface provider = llmConfig.createProvider(entity, getDecryptedApiKey(providerId));
            long start = System.currentTimeMillis();
            float[] vec = provider.embed("hello", model);
            long duration = System.currentTimeMillis() - start;
            return TestConnectionResult.builder()
                    .success(true)
                    .message("连接成功 (维度 " + vec.length + ")")
                    .model(model)
                    .durationMs(duration)
                    .build();
        } catch (Exception e) {
            log.warn("embedding 连通测试失败 [provider={}]: {}", entity.getName(), e.getMessage());
            return TestConnectionResult.fail(extractRootMessage(e));
        }
    }

    /**
     * 用指定实体和API Key测试连通（供 UserLlm 复用）
     */
    public TestConnectionResult testConnection(LlmProviderEntity entity, String apiKey) {
        return doTestConnection(entity, apiKey);
    }

    private TestConnectionResult doTestConnection(LlmProviderEntity entity, String apiKey) {
        String model = pickFirstModel(entity);
        if (model == null) {
            return TestConnectionResult.fail("未配置模型列表");
        }
        if (entity.getApiEndpoint() == null || entity.getApiEndpoint().isBlank()) {
            return TestConnectionResult.fail("未配置API端点");
        }

        try {
            LlmProviderInterface provider = llmConfig.createProvider(entity, apiKey != null ? apiKey : "");
            LlmRequest testRequest = LlmRequest.builder()
                    .model(model)
                    .messages(List.of(new LlmMessage("user", "Hi")))
                    .maxTokens(5)
                    .temperature(0.0)
                    .stream(false)
                    .build();
            LlmResponse response = provider.chat(testRequest);
            return TestConnectionResult.success(response.getModel(), response.getDuration());
        } catch (Exception e) {
            log.warn("LLM连通测试失败 [provider={}]: {}", entity.getName(), e.getMessage());
            return TestConnectionResult.fail(extractRootMessage(e));
        }
    }

    private String pickFirstModel(LlmProviderEntity entity) {
        if (entity.getModels() == null || entity.getModels().isBlank()) return null;
        try {
            List<String> models = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(entity.getModels(), List.class);
            return models.isEmpty() ? null : models.get(0);
        } catch (Exception e) {
            return null;
        }
    }

    private String extractRootMessage(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String msg = cause.getMessage();
        if (msg != null && msg.length() > 200) {
            msg = msg.substring(0, 200) + "...";
        }
        return msg;
    }

    private LlmProviderVO toVO(LlmProviderEntity entity) {
        return toVO(entity, null);
    }

    private LlmProviderVO toVO(LlmProviderEntity entity, Integer activeEmbeddingDim) {
        return LlmProviderVO.builder()
                .id(entity.getId())
                .name(entity.getName())
                .displayName(entity.getDisplayName())
                .protocol(entity.getProtocol())
                .apiEndpoint(entity.getApiEndpoint())
                .models(entity.getModels())
                .config(entity.getConfig())
                .status(entity.getStatus())
                .sortOrder(entity.getSortOrder())
                .category(entity.getCategory())
                .dim("EMBEDDING".equals(entity.getCategory()) ? activeEmbeddingDim : null)
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
