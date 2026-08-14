package com.superprogrammer.llm.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.superprogrammer.billing.context.BillingContext;
import com.superprogrammer.billing.entity.LlmUsageLogEntity;
import com.superprogrammer.billing.service.LlmBillingService;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.knowledge.util.TokenEstimator;
import com.superprogrammer.llm.config.LlmConfig;
import com.superprogrammer.llm.dto.LlmProviderVO;
import com.superprogrammer.llm.dto.LlmProviderExportItem;
import com.superprogrammer.llm.dto.ProviderImportResult;
import com.superprogrammer.llm.entity.LlmProviderEntity;
import com.superprogrammer.llm.mapper.LlmProviderMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import com.superprogrammer.llm.dto.LlmMessage;
import com.superprogrammer.llm.dto.LlmRequest;
import com.superprogrammer.llm.dto.LlmResponse;
import com.superprogrammer.llm.dto.TestConnectionResult;
import com.superprogrammer.llm.dto.RerankRequest;
import com.superprogrammer.llm.dto.RerankResult;
import com.superprogrammer.llm.provider.LlmProviderInterface;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
public class LlmProviderService {

    /** CHAT = 对话 provider，进 chat 路由/模型列表。 */
    public static final String CATEGORY_CHAT = "CHAT";
    /** EMBEDDING = 向量 provider，只走 embed 路由，不进 chat 模型列表。 */
    public static final String CATEGORY_EMBEDDING = "EMBEDDING";
    /** VIDEO = 视频生成等任务型 provider，不参与 chat 路由（媒体侧按 category 单独取）。 */
    public static final String CATEGORY_VIDEO = "VIDEO";
    /** IMAGE = 生图 provider（预留，画布 R-3 接入），不进 chat 路由/视频目录。 */
    public static final String CATEGORY_IMAGE = "IMAGE";
    /** RERANK = 专用语义重排模型；与 CHAT 分开，避免把普通对话模型误当专用重排。 */
    public static final String CATEGORY_RERANK = "RERANK";

    private static final String DEFAULT_CATEGORY = CATEGORY_CHAT;
    /** category 白名单四分（FR-002）；CHAT_EMBEDDING / MEDIA 已由 V60 迁移废弃。 */
    private static final Set<String> CATEGORIES =
            Set.of(CATEGORY_CHAT, CATEGORY_EMBEDDING, CATEGORY_VIDEO, CATEGORY_IMAGE, CATEGORY_RERANK);

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
    /** 计费：admin「测试连通」按 admin 钱包计费（直调 provider 测特定实例，gateway 按 model 路由会测错）。 */
    private final LlmBillingService billingService;

    public LlmProviderService(LlmProviderMapper mapper, AesEncryptService aesEncryptService, @Lazy LlmConfig llmConfig,
                              com.superprogrammer.llm.mapper.EmbeddingModelVersionMapper embeddingModelVersionMapper,
                              LlmBillingService billingService) {
        this.mapper = mapper;
        this.aesEncryptService = aesEncryptService;
        this.llmConfig = llmConfig;
        this.embeddingModelVersionMapper = embeddingModelVersionMapper;
        this.billingService = billingService;
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

    /** 返回指定类型当前启用的明确模型列表，供管理员默认模型校验与下拉展示。 */
    public List<String> listActiveModels(String category) {
        return listActive().stream()
                .filter(provider -> category.equalsIgnoreCase(provider.getCategory()))
                .flatMap(provider -> parseModelList(provider.getModels()).stream())
                .filter(model -> model != null && !model.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    private List<String> parseModelList(String modelsJson) {
        if (modelsJson == null || modelsJson.isBlank()) return List.of();
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(modelsJson,
                    new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("供应商模型列表解析失败: {}", e.getMessage());
            return List.of();
        }
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

    /**
     * 导出全量供应商为明文 Key 列表（问题 10x-2）。
     * <p>仅 admin 可调（Controller 层 @RequirePermission("role:manage")）。
     * 解密 apiKeyEnc 填入 apiKey 明文——导出文件含明文密钥，调用方须妥善保管。
     * 解密失败的条目 apiKey 置 null 并 warn（不中断整体导出）。
     */
    public List<LlmProviderExportItem> exportAll() {
        LambdaQueryWrapper<LlmProviderEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(LlmProviderEntity::getDeleted, 0)
               .orderByAsc(LlmProviderEntity::getSortOrder);
        return mapper.selectList(wrapper).stream()
                .map(this::toExportItem)
                .collect(Collectors.toList());
    }

    private LlmProviderExportItem toExportItem(LlmProviderEntity e) {
        LlmProviderExportItem item = new LlmProviderExportItem();
        item.setName(e.getName());
        item.setDisplayName(e.getDisplayName());
        item.setProtocol(e.getProtocol());
        item.setApiEndpoint(e.getApiEndpoint());
        // 解密 key 填明文；失败不中断导出，置 null 并 warn
        if (e.getApiKeyEnc() != null && !e.getApiKeyEnc().isBlank()) {
            try {
                item.setApiKey(aesEncryptService.decrypt(e.getApiKeyEnc()));
            } catch (Exception ex) {
                log.warn("导出供应商解密 key 失败 name={}: {}", e.getName(), ex.getMessage());
            }
        }
        item.setModels(e.getModels());
        item.setConfig(e.getConfig());
        item.setSortOrder(e.getSortOrder());
        item.setCategory(e.getCategory());
        item.setStatus(e.getStatus());
        return item;
    }

    /** 导入单批上限（防恶意巨大体导致 OOM/长事务）。 */
    private static final int IMPORT_MAX_SIZE = 200;

    /**
     * 批量导入供应商（问题 10x-2）：按 name upsert，非法行跳过记入 errors。
     * <p>规则：
     * <ul>
     *   <li>size > 200 → 抛 BAD_REQUEST（防滥用）；</li>
     *   <li>逐条校验：name 非空、apiEndpoint 是 http(s) URL、category 白名单（normalizeCategory 容错）；</li>
     *   <li>upsert by name：存在→更新非空字段（apiKey 空→保留原 key），不存在→新建；</li>
     *   <li>导入完成后 {@link LlmConfig#reload()} 让新配置即时生效。</li>
     * </ul>
     */
    public ProviderImportResult importAll(List<LlmProviderExportItem> items) {
        if (items == null) {
            items = List.of();
        }
        if (items.size() > IMPORT_MAX_SIZE) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "单次导入上限 " + IMPORT_MAX_SIZE + " 条，当前 " + items.size() + " 条");
        }
        ProviderImportResult result = ProviderImportResult.builder().build();
        for (int i = 0; i < items.size(); i++) {
            LlmProviderExportItem item = items.get(i);
            int lineNo = i + 1;
            try {
                upsertByName(item, result);
            } catch (Exception e) {
                log.warn("导入第{}行失败 name={}: {}", lineNo, item.getName(), e.getMessage());
                result.incFailed("第" + lineNo + "行 name=" + item.getName() + " 失败: " + e.getMessage());
            }
        }
        llmConfig.reload();
        return result;
    }

    private void upsertByName(LlmProviderExportItem item, ProviderImportResult result) {
        // 校验：name 非空
        if (item.getName() == null || item.getName().isBlank()) {
            result.incFailed("name 为空");
            return;
        }
        // 校验：apiEndpoint 非空且 http(s)
        String endpoint = item.getApiEndpoint();
        if (endpoint == null || endpoint.isBlank()) {
            result.incFailed("apiEndpoint 为空");
            return;
        }
        if (!endpoint.startsWith("http://") && !endpoint.startsWith("https://")) {
            result.incFailed("apiEndpoint 非 http(s) URL");
            return;
        }

        String category = normalizeCategory(item.getCategory());
        LlmProviderEntity existing = getByName(item.getName());
        if (existing != null) {
            // 更新非空字段（apiKey 空→保留原 key，不覆盖）
            if (item.getDisplayName() != null) existing.setDisplayName(item.getDisplayName());
            if (item.getProtocol() != null) existing.setProtocol(item.getProtocol());
            existing.setApiEndpoint(endpoint);
            if (item.getApiKey() != null && !item.getApiKey().isBlank()) {
                existing.setApiKeyEnc(aesEncryptService.encrypt(item.getApiKey()));
            }
            if (item.getModels() != null) existing.setModels(item.getModels());
            if (item.getConfig() != null) existing.setConfig(item.getConfig());
            if (item.getSortOrder() != null) existing.setSortOrder(item.getSortOrder());
            existing.setCategory(category);
            if (item.getStatus() != null) existing.setStatus(item.getStatus());
            mapper.updateById(existing);
            result.incUpdated();
        } else {
            LlmProviderEntity entity = new LlmProviderEntity();
            entity.setName(item.getName());
            entity.setDisplayName(item.getDisplayName());
            entity.setProtocol(item.getProtocol() != null ? item.getProtocol() : "OPENAI_COMPATIBLE");
            entity.setApiEndpoint(endpoint);
            if (item.getApiKey() != null && !item.getApiKey().isBlank()) {
                entity.setApiKeyEnc(aesEncryptService.encrypt(item.getApiKey()));
            }
            entity.setModels(item.getModels());
            entity.setConfig(item.getConfig());
            entity.setSortOrder(item.getSortOrder() != null ? item.getSortOrder() : 0);
            entity.setCategory(category);
            entity.setStatus(item.getStatus() != null ? item.getStatus() : "ACTIVE");
            mapper.insert(entity);
            result.incCreated();
        }
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

    /** 按 id 取 provider（未软删）。媒体任务 worker 按任务落库的 providerId 路由用。 */
    public LlmProviderEntity getById(Long id) {
        if (id == null) {
            return null;
        }
        LlmProviderEntity entity = mapper.selectById(id);
        return entity != null && Integer.valueOf(0).equals(entity.getDeleted()) ? entity : null;
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
        // ANTHROPIC+EMBEDDING 组合不成立：Claude 无 embedding 接口，给明确话术而非上游 404。
        if (isAnthropic(entity)) {
            return TestConnectionResult.fail("Claude（ANTHROPIC 协议）不提供 embedding 接口，请改用 OPENAI_COMPATIBLE 协议的向量服务");
        }
        try {
            LlmProviderInterface provider = llmConfig.createProvider(entity, getDecryptedApiKey(providerId));
            long start = System.currentTimeMillis();
            float[] vec = provider.embed("hello", model);
            long duration = System.currentTimeMillis() - start;
            // 计费：admin 测试按 admin 钱包扣（embed 2-arg 无 usage，估算 input token；uid 取 BillingContext）
            chargeAdminDiagnostic(entity.getId(), model, LlmUsageLogEntity.KIND_EMBED,
                    TokenEstimator.estimate("hello"), 0);
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

    /** Rerank 专用连通测试：直测所选 Provider，不经模型名全局路由。 */
    public TestConnectionResult testRerank(Long providerId) {
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
        if (isAnthropic(entity)) {
            return TestConnectionResult.fail("ANTHROPIC 协议不提供专用 rerank 接口");
        }
        List<String> documents = List.of(
                "文本排序模型广泛用于搜索引擎和推荐系统中，它们根据文本相关性对候选文本进行排序",
                "量子计算是计算科学的一个前沿领域",
                "预训练语言模型的发展给文本排序模型带来了新的进展");
        try {
            LlmProviderInterface provider = llmConfig.createProvider(entity, getDecryptedApiKey(providerId));
            long start = System.currentTimeMillis();
            RerankResult result = provider.rerank(RerankRequest.builder()
                    .model(model)
                    .query("什么是文本排序模型")
                    .documents(documents)
                    .topN(2)
                    .build());
            long duration = result.getDuration() != null ? result.getDuration() : System.currentTimeMillis() - start;
            com.superprogrammer.llm.dto.TokenUsage usage = result.getUsage();
            int inputTokens = usage != null ? usage.getPromptTokens()
                    : TokenEstimator.estimate("什么是文本排序模型")
                    + documents.stream().mapToInt(TokenEstimator::estimate).sum();
            chargeAdminDiagnostic(entity.getId(), model, LlmUsageLogEntity.KIND_RERANK, inputTokens, 0);
            return TestConnectionResult.builder()
                    .success(true)
                    .message("连接成功 (返回 " + result.getItems().size() + " 条排序结果)")
                    .model(model)
                    .durationMs(duration)
                    .build();
        } catch (Exception e) {
            log.warn("rerank 连通测试失败 [provider={}]: {}", entity.getName(), e.getClass().getSimpleName());
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
        // FR-004 测试分流：IMAGE 是生图预留位，provider 未接入，点「测试」不发请求直接给话术。
        if (CATEGORY_IMAGE.equalsIgnoreCase(entity.getCategory())) {
            return TestConnectionResult.fail("生图（IMAGE）provider 尚未接入，配置已保存，待生图功能上线后开放测试");
        }
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
            // 计费：admin 测试按 admin 钱包扣（chat 取真实 usage，无则估 input）
            com.superprogrammer.llm.dto.TokenUsage u = response.getUsage();
            Integer in = u != null ? u.getPromptTokens() : null;
            Integer out = u != null ? u.getCompletionTokens() : null;
            if (in == null && out == null) {
                in = TokenEstimator.estimate("Hi");
                out = 0;
            }
            chargeAdminDiagnostic(entity.getId(), model, LlmUsageLogEntity.KIND_CHAT, in, out);
            return TestConnectionResult.success(response.getModel(), response.getDuration());
        } catch (Exception e) {
            log.warn("LLM连通测试失败 [provider={}]: {}", entity.getName(), e.getMessage());
            return TestConnectionResult.fail(extractRootMessage(e));
        }
    }

    /**
     * admin「测试连通」诊断调用计费：直调 provider 测特定 providerId（gateway 按 model 路由会测错实例），
     * 故不走 gateway，调完手动按 admin 钱包结算。uid 取 {@link BillingContext#current()}（admin 已认证），
     * 全链吞异常（诊断计费失败不得让测试按钮报错）。无 uid（异常无上下文）→ onSuccess 仅采不扣。
     */
    private void chargeAdminDiagnostic(Long providerId, String model, String kind, Integer tokensInput, Integer tokensOutput) {
        try {
            billingService.onSuccess(BillingContext.current(), providerId, "GLOBAL",
                    model, kind, tokensInput, tokensOutput);
        } catch (Exception e) {
            log.warn("admin 诊断计费失败(吞) provider={} model={}: {}", providerId, model, e.getMessage());
        }
    }

    /** 判定 ANTHROPIC 协议（显式 protocol 优先，缺省沿用 name=claude 推断，与 LlmConfig 口径一致）。 */
    private boolean isAnthropic(LlmProviderEntity entity) {
        String protocol = entity.getProtocol();
        if (protocol != null && !protocol.isBlank()) {
            return "ANTHROPIC".equalsIgnoreCase(protocol.trim());
        }
        return "claude".equals(entity.getName());
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
                .dim(CATEGORY_EMBEDDING.equals(entity.getCategory()) ? activeEmbeddingDim : null)
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
