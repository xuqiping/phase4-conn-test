package com.superprogrammer.knowledge.service;

import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.knowledge.entity.RagRankingConfig;
import com.superprogrammer.knowledge.dto.RankingConfigUpdateRequest;
import com.superprogrammer.knowledge.mapper.RagRankingConfigMapper;
import com.superprogrammer.knowledge.mapper.RagAnswerCacheMapper;
import com.superprogrammer.llm.service.LlmProviderService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Ranking 配置解析咽喉：知识库覆盖 → 管理员默认 → 明确报错。
 * 禁止从供应商列表第一项推断模型，也禁止显式模型不可用时静默替换。
 */
@Service
@RequiredArgsConstructor
public class RankingConfigService {

    public enum Source { KNOWLEDGE_BASE, ADMIN_DEFAULT }

    public record ResolvedRankingConfig(
            Long configId,
            String mode,
            String model,
            String configVersion,
            int candidateLimit,
            int finalLimit,
            int batchSize,
            int timeoutMs,
            String fallbackPolicy,
            boolean highAccuracyEnabled,
            Source source) {
    }

    private final RagRankingConfigMapper mapper;
    private final LlmProviderService providerService;
    private final RagAnswerCacheMapper answerCacheMapper;

    public ResolvedRankingConfig resolve(Long kbId) {
        String routedVersion = RagRankingRouteContext.currentVersion();
        if (routedVersion != null) return resolveVersion(kbId, routedVersion);
        RagRankingConfig config = kbId == null ? null : mapper.findActiveForKb(kbId);
        Source source = Source.KNOWLEDGE_BASE;
        if (config == null) {
            config = mapper.findActiveDefault();
            source = Source.ADMIN_DEFAULT;
        }
        if (config == null) {
            throw new BusinessException(ErrorCode.UNPROCESSABLE,
                    "知识库未配置重排模式，且管理员未设置默认配置");
        }

        return validateResolved(config, source);
    }

    public ResolvedRankingConfig resolveVersion(Long kbId, String configVersion) {
        String version = trimToNull(configVersion);
        if (version == null) throw new BusinessException(ErrorCode.BAD_REQUEST, "重排配置版本不能为空");
        RagRankingConfig config = kbId == null ? null : mapper.findForKbByVersion(kbId, version);
        Source source = Source.KNOWLEDGE_BASE;
        if (config == null) {
            config = mapper.findDefaultByVersion(version);
            source = Source.ADMIN_DEFAULT;
        }
        if (config == null) {
            throw new BusinessException(ErrorCode.UNPROCESSABLE, "知识库重排配置版本不存在: " + version);
        }
        return validateResolved(config, source);
    }

    private ResolvedRankingConfig validateResolved(RagRankingConfig config, Source source) {
        String mode = normalizeMode(config.getRankingMode());
        String model = trimToNull(config.getModel());
        if (!"DISABLED".equals(mode)) {
            if (model == null) {
                throw new BusinessException(ErrorCode.UNPROCESSABLE, "知识库重排模型未配置");
            }
            String category = "LLM".equals(mode)
                    ? LlmProviderService.CATEGORY_CHAT
                    : LlmProviderService.CATEGORY_RERANK;
            List<String> available = providerService.listActiveModels(category);
            if (!available.contains(model)) {
                throw new BusinessException(ErrorCode.UNPROCESSABLE,
                        "知识库重排模型不可用: " + model);
            }
        } else {
            model = null;
        }

        return new ResolvedRankingConfig(
                config.getId(), mode, model, config.getConfigVersion(),
                valueOr(config.getCandidateLimit(), 30),
                valueOr(config.getFinalLimit(), 10),
                valueOr(config.getBatchSize(), 10),
                valueOr(config.getTimeoutMs(), 4000),
                trimOr(config.getFallbackPolicy(), "FAIL_CLOSED"),
                Boolean.TRUE.equals(config.getHighAccuracyEnabled()), source);
    }

    @Transactional
    public ResolvedRankingConfig saveForKb(Long kbId, RankingConfigUpdateRequest request, Long userId) {
        if (kbId == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "知识库 ID 不能为空");
        }
        RagRankingConfig config = buildConfig(kbId, request, userId);
        mapper.archiveActiveForKb(kbId, userId);
        mapper.insert(config);
        answerCacheMapper.invalidateByKb(kbId);
        return toResolved(config, Source.KNOWLEDGE_BASE);
    }

    @Transactional
    public ResolvedRankingConfig saveDefault(RankingConfigUpdateRequest request, Long userId) {
        RagRankingConfig config = buildConfig(null, request, userId);
        mapper.archiveActiveDefault(userId);
        mapper.insert(config);
        answerCacheMapper.invalidateAllActive();
        return toResolved(config, Source.ADMIN_DEFAULT);
    }

    private RagRankingConfig buildConfig(Long kbId, RankingConfigUpdateRequest request, Long userId) {
        if (request == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "重排配置不能为空");
        }
        String mode = normalizeMode(request.getRankingMode());
        String model = trimToNull(request.getModel());
        if ("DISABLED".equals(mode)) {
            model = null;
        } else {
            if (model == null) {
                throw new BusinessException(ErrorCode.UNPROCESSABLE, "知识库重排模型未配置");
            }
            String category = "LLM".equals(mode)
                    ? LlmProviderService.CATEGORY_CHAT : LlmProviderService.CATEGORY_RERANK;
            if (!providerService.listActiveModels(category).contains(model)) {
                throw new BusinessException(ErrorCode.UNPROCESSABLE, "知识库重排模型不可用: " + model);
            }
        }
        int candidates = valueOr(request.getCandidateLimit(), 30);
        int finals = valueOr(request.getFinalLimit(), 10);
        if (finals > candidates) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "最终证据数不能超过重排候选数");
        }
        RagRankingConfig config = new RagRankingConfig();
        config.setTenantId(1L);
        config.setKbId(kbId);
        config.setRankingMode(mode);
        config.setModel(model);
        config.setCandidateLimit(candidates);
        config.setFinalLimit(finals);
        config.setBatchSize(valueOr(request.getBatchSize(), 10));
        config.setTimeoutMs(valueOr(request.getTimeoutMs(), 4000));
        config.setFallbackPolicy(trimOr(request.getFallbackPolicy(), "FAIL_CLOSED"));
        config.setHighAccuracyEnabled(Boolean.TRUE.equals(request.getHighAccuracyEnabled()));
        config.setConfigVersion("rc-" + UUID.randomUUID().toString().replace("-", ""));
        config.setStatus("ACTIVE");
        config.setCreatedBy(userId);
        config.setUpdatedBy(userId);
        return config;
    }

    private ResolvedRankingConfig toResolved(RagRankingConfig config, Source source) {
        return new ResolvedRankingConfig(config.getId(), config.getRankingMode(), config.getModel(),
                config.getConfigVersion(), config.getCandidateLimit(), config.getFinalLimit(),
                config.getBatchSize(), config.getTimeoutMs(), config.getFallbackPolicy(),
                Boolean.TRUE.equals(config.getHighAccuracyEnabled()), source);
    }

    private static String normalizeMode(String raw) {
        String mode = trimToNull(raw);
        if (mode == null) {
            throw new BusinessException(ErrorCode.UNPROCESSABLE, "知识库重排模式未配置");
        }
        mode = mode.toUpperCase(Locale.ROOT);
        if (!List.of("LLM", "RERANK", "DISABLED").contains(mode)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "不支持的知识库重排模式: " + raw);
        }
        return mode;
    }

    private static int valueOr(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private static String trimOr(String value, String fallback) {
        String normalized = trimToNull(value);
        return normalized == null ? fallback : normalized;
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
