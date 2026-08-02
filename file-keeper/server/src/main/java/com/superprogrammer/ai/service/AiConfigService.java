package com.superprogrammer.ai.service;

import com.superprogrammer.ai.dto.AiConfigCreateRequest;
import com.superprogrammer.ai.dto.AiConfigUpdateRequest;
import com.superprogrammer.ai.dto.AiConfigVO;
import com.superprogrammer.ai.entity.AiConfig;
import com.superprogrammer.ai.repository.AiConfigRepository;
import com.superprogrammer.common.BusinessException;
import com.superprogrammer.common.ErrorCode;
import com.superprogrammer.workreport.service.CredentialEncryptor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AiConfigService {

    private final AiConfigRepository aiConfigRepository;
    private final CredentialEncryptor credentialEncryptor;

    @Transactional(readOnly = true)
    public List<AiConfigVO> listByUserId(Long userId) {
        return aiConfigRepository.findByUserId(userId).stream()
                .map(this::toVO)
                .toList();
    }

    @Transactional(readOnly = true)
    public AiConfigVO getByIdAndUserId(Long id, Long userId) {
        AiConfig config = aiConfigRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "AI 配置不存在"));
        return toVO(config);
    }

    @Transactional
    public AiConfigVO create(Long userId, AiConfigCreateRequest request) {
        AiConfig config = new AiConfig();
        config.setUserId(userId);
        config.setName(request.name().trim());
        config.setProvider(request.provider().trim().toLowerCase());
        config.setModel(request.model().trim());
        config.setApiKeyEnc(encryptKey(request.apiKey()));
        config.setEndpoint(trimToNull(request.endpoint()));
        config.setMaxTokens(request.maxTokens());
        config.setTimeoutSeconds(request.timeoutSeconds());
        config.setEnabled(request.enabled() != null ? request.enabled() : true);
        config.setCreatedBy(userId);
        config.setUpdatedBy(userId);

        boolean isDefault = request.isDefault() != null && request.isDefault();
        if (isDefault) {
            aiConfigRepository.clearDefaultByUserId(userId, userId);
        }
        config.setIsDefault(isDefault);

        AiConfig saved = aiConfigRepository.insert(config);
        return toVO(saved);
    }

    @Transactional
    public AiConfigVO update(Long userId, Long id, AiConfigUpdateRequest request) {
        AiConfig config = aiConfigRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "AI 配置不存在"));

        config.setName(request.name().trim());
        config.setProvider(request.provider().trim().toLowerCase());
        config.setModel(request.model().trim());
        if (request.apiKey() != null && !request.apiKey().isBlank()) {
            config.setApiKeyEnc(encryptKey(request.apiKey()));
        }
        config.setEndpoint(trimToNull(request.endpoint()));
        config.setMaxTokens(request.maxTokens());
        config.setTimeoutSeconds(request.timeoutSeconds());
        config.setEnabled(request.enabled() != null ? request.enabled() : config.getEnabled());
        config.setUpdatedBy(userId);

        boolean isDefault = request.isDefault() != null && request.isDefault();
        if (isDefault && !Boolean.TRUE.equals(config.getIsDefault())) {
            aiConfigRepository.clearDefaultByUserId(userId, userId);
        }
        config.setIsDefault(isDefault);

        AiConfig saved = aiConfigRepository.update(config);
        return toVO(saved);
    }

    @Transactional
    public void delete(Long userId, Long id) {
        AiConfig config = aiConfigRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "AI 配置不存在"));
        aiConfigRepository.softDeleteById(config.getId(), userId);
    }

    @Transactional
    public AiConfigVO setDefault(Long userId, Long id) {
        AiConfig config = aiConfigRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "AI 配置不存在"));
        if (!Boolean.TRUE.equals(config.getEnabled())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "禁用的配置不能设为默认");
        }
        aiConfigRepository.clearDefaultByUserId(userId, userId);
        config.setIsDefault(true);
        config.setUpdatedBy(userId);
        AiConfig saved = aiConfigRepository.update(config);
        return toVO(saved);
    }

    @Transactional(readOnly = true)
    public AiConfigVO getEffectiveConfig(Long userId, Long aiConfigId) {
        AiConfig config;
        if (aiConfigId != null) {
            config = aiConfigRepository.findByIdAndUserId(aiConfigId, userId).orElse(null);
        } else {
            config = aiConfigRepository.findDefaultByUserId(userId).orElse(null);
        }
        if (config == null || !Boolean.TRUE.equals(config.getEnabled())) {
            return null;
        }
        return toVO(config);
    }

    @Transactional(readOnly = true)
    public String getDecryptedApiKey(Long userId, Long aiConfigId) {
        AiConfig config;
        if (aiConfigId != null) {
            config = aiConfigRepository.findByIdAndUserId(aiConfigId, userId).orElse(null);
        } else {
            config = aiConfigRepository.findDefaultByUserId(userId).orElse(null);
        }
        if (config == null || config.getApiKeyEnc() == null) {
            return null;
        }
        return credentialEncryptor.decrypt(config.getApiKeyEnc());
    }

    private String encryptKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }
        return credentialEncryptor.encrypt(apiKey.trim());
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private AiConfigVO toVO(AiConfig config) {
        return new AiConfigVO(
                config.getId(),
                config.getName(),
                config.getProvider(),
                config.getModel(),
                config.getEndpoint(),
                config.getMaxTokens(),
                config.getTimeoutSeconds(),
                config.getIsDefault(),
                config.getEnabled()
        );
    }
}
