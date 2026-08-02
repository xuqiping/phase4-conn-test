package com.superprogrammer.workreport.service;

import com.superprogrammer.common.BusinessException;
import com.superprogrammer.common.ErrorCode;
import com.superprogrammer.workreport.dto.PushTargetCreateRequest;
import com.superprogrammer.workreport.dto.PushTargetDto;
import com.superprogrammer.workreport.dto.PushTargetUpdateRequest;
import com.superprogrammer.workreport.entity.PushCredential;
import com.superprogrammer.workreport.entity.PushTarget;
import com.superprogrammer.workreport.repository.PushCredentialRepository;
import com.superprogrammer.workreport.repository.PushTargetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PushTargetService {

    private final PushTargetRepository pushTargetRepository;
    private final PushCredentialRepository pushCredentialRepository;

    @Transactional(readOnly = true)
    public List<PushTargetDto> listByUser(Long userId) {
        List<PushTarget> targets = pushTargetRepository.findByUserId(userId);
        List<Long> credentialIds = targets.stream()
            .map(PushTarget::getCredentialId)
            .distinct()
            .toList();
        Map<Long, String> credentialNames = pushCredentialRepository.findByIds(credentialIds).stream()
            .collect(Collectors.toMap(PushCredential::getId, PushCredential::getName));
        return targets.stream()
            .map(t -> toDto(t, credentialNames.get(t.getCredentialId())))
            .toList();
    }

    @Transactional(readOnly = true)
    public PushTargetDto getById(Long userId, Long id) {
        PushTarget target = requireOwnedByUser(id, userId);
        String credentialName = pushCredentialRepository.findById(target.getCredentialId())
            .map(PushCredential::getName)
            .orElse(null);
        return toDto(target, credentialName);
    }

    @Transactional(readOnly = true)
    public PushTarget requireOwnedByUser(Long id, Long userId) {
        return pushTargetRepository.findByIdAndUserId(id, userId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "推送目标不存在"));
    }

    @Transactional(readOnly = true)
    public List<PushTarget> listByIds(Long userId, List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<PushTarget> targets = pushTargetRepository.findByIds(ids);
        return targets.stream()
            .filter(t -> t.getUserId().equals(userId))
            .toList();
    }

    @Transactional
    public PushTargetDto create(Long userId, PushTargetCreateRequest request) {
        validateCredential(userId, request.credentialId());
        PushTarget target = new PushTarget();
        target.setUserId(userId);
        target.setName(request.name().trim());
        target.setPlatform(request.platform().trim().toUpperCase());
        target.setTargetType(request.targetType().trim().toUpperCase());
        target.setTargetId(request.targetId().trim());
        target.setCredentialId(request.credentialId());
        target.setCreatedBy(userId);
        target.setUpdatedBy(userId);
        PushTarget saved = pushTargetRepository.insert(target);
        String credentialName = pushCredentialRepository.findById(saved.getCredentialId())
            .map(PushCredential::getName)
            .orElse(null);
        return toDto(saved, credentialName);
    }

    @Transactional
    public PushTargetDto update(Long userId, Long id, PushTargetUpdateRequest request) {
        PushTarget target = requireOwnedByUser(id, userId);
        validateCredential(userId, request.credentialId());
        target.setName(request.name().trim());
        target.setPlatform(request.platform().trim().toUpperCase());
        target.setTargetType(request.targetType().trim().toUpperCase());
        target.setTargetId(request.targetId().trim());
        target.setCredentialId(request.credentialId());
        target.setUpdatedBy(userId);
        PushTarget saved = pushTargetRepository.update(target);
        String credentialName = pushCredentialRepository.findById(saved.getCredentialId())
            .map(PushCredential::getName)
            .orElse(null);
        return toDto(saved, credentialName);
    }

    @Transactional
    public void delete(Long userId, Long id) {
        PushTarget target = requireOwnedByUser(id, userId);
        pushTargetRepository.softDeleteById(target.getId(), userId);
    }

    private void validateCredential(Long userId, Long credentialId) {
        pushCredentialRepository.findByIdAndUserId(credentialId, userId)
            .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "凭据不存在或不属于当前用户"));
    }

    private PushTargetDto toDto(PushTarget target, String credentialName) {
        return new PushTargetDto(
            target.getId(),
            target.getName(),
            target.getPlatform(),
            target.getTargetType(),
            target.getTargetId(),
            target.getCredentialId(),
            credentialName
        );
    }
}
