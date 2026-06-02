package com.superprogrammer.llm.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.llm.dto.UserLlmProviderVO;
import com.superprogrammer.llm.entity.UserLlmProviderEntity;
import com.superprogrammer.llm.mapper.UserLlmProviderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserLlmProviderService {

    private final UserLlmProviderMapper mapper;
    private final AesEncryptService aesEncryptService;

    public UserLlmProviderEntity createOrUpdate(Long userId, UserLlmProviderEntity entity) {
        entity.setUserId(userId);

        LambdaQueryWrapper<UserLlmProviderEntity> wrapper = new LambdaQueryWrapper<UserLlmProviderEntity>()
                .eq(UserLlmProviderEntity::getUserId, userId)
                .eq(UserLlmProviderEntity::getProviderName, entity.getProviderName());
        UserLlmProviderEntity existing = mapper.selectOne(wrapper);

        if (existing != null) {
            if (entity.getApiEndpoint() != null) existing.setApiEndpoint(entity.getApiEndpoint());
            if (entity.getApiKeyEnc() != null && !entity.getApiKeyEnc().isBlank()) {
                existing.setApiKeyEnc(aesEncryptService.encrypt(entity.getApiKeyEnc()));
            }
            if (entity.getModels() != null) existing.setModels(entity.getModels());
            existing.setStatus("ACTIVE");
            mapper.updateById(existing);
            return existing;
        } else {
            if (entity.getApiKeyEnc() != null && !entity.getApiKeyEnc().isBlank()) {
                entity.setApiKeyEnc(aesEncryptService.encrypt(entity.getApiKeyEnc()));
            }
            entity.setStatus("ACTIVE");
            mapper.insert(entity);
            return entity;
        }
    }

    public List<UserLlmProviderVO> listByUser(Long userId) {
        LambdaQueryWrapper<UserLlmProviderEntity> wrapper = new LambdaQueryWrapper<UserLlmProviderEntity>()
                .eq(UserLlmProviderEntity::getUserId, userId)
                .orderByAsc(UserLlmProviderEntity::getProviderName);
        return mapper.selectList(wrapper).stream()
                .map(this::toVO)
                .collect(Collectors.toList());
    }

    public void delete(Long userId, Long id) {
        UserLlmProviderEntity entity = mapper.selectById(id);
        if (entity == null || !entity.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "供应商配置不存在");
        }
        mapper.deleteById(id);
    }

    public String getDecryptedApiKey(Long userId, Long id) {
        UserLlmProviderEntity entity = mapper.selectById(id);
        if (entity == null || !entity.getUserId().equals(userId)) {
            return null;
        }
        return entity.getApiKeyEnc() != null ? aesEncryptService.decrypt(entity.getApiKeyEnc()) : null;
    }

    public UserLlmProviderEntity findByUserAndProviderName(Long userId, String providerName) {
        LambdaQueryWrapper<UserLlmProviderEntity> wrapper = new LambdaQueryWrapper<UserLlmProviderEntity>()
                .eq(UserLlmProviderEntity::getUserId, userId)
                .eq(UserLlmProviderEntity::getProviderName, providerName)
                .eq(UserLlmProviderEntity::getStatus, "ACTIVE");
        return mapper.selectOne(wrapper);
    }

    private UserLlmProviderVO toVO(UserLlmProviderEntity entity) {
        return UserLlmProviderVO.builder()
                .id(entity.getId())
                .providerName(entity.getProviderName())
                .apiEndpoint(entity.getApiEndpoint())
                .hasApiKey(entity.getApiKeyEnc() != null && !entity.getApiKeyEnc().isBlank())
                .models(entity.getModels())
                .status(entity.getStatus())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
