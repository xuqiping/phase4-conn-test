package com.superprogrammer.workreport.service;

import com.superprogrammer.common.BusinessException;
import com.superprogrammer.common.ErrorCode;
import com.superprogrammer.workreport.dto.PushCredentialCreateRequest;
import com.superprogrammer.workreport.dto.PushCredentialDto;
import com.superprogrammer.workreport.dto.PushCredentialUpdateRequest;
import com.superprogrammer.workreport.entity.PushCredential;
import com.superprogrammer.workreport.repository.PushCredentialRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class PushCredentialService {

    private final PushCredentialRepository pushCredentialRepository;
    private final CredentialEncryptor credentialEncryptor;

    @Transactional(readOnly = true)
    public List<PushCredentialDto> listByUser(Long userId) {
        return pushCredentialRepository.findByUserId(userId).stream()
            .map(this::toDto)
            .toList();
    }

    @Transactional(readOnly = true)
    public PushCredentialDto getById(Long userId, Long id) {
        PushCredential credential = requireOwnedByUser(id, userId);
        return toDto(credential);
    }

    @Transactional(readOnly = true)
    public PushCredential requireOwnedByUser(Long id, Long userId) {
        return pushCredentialRepository.findByIdAndUserId(id, userId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "推送凭据不存在"));
    }

    @Transactional
    public PushCredentialDto create(Long userId, PushCredentialCreateRequest request) {
        PushCredential credential = new PushCredential();
        credential.setUserId(userId);
        credential.setName(request.name().trim());
        credential.setPlatform(request.platform().trim().toUpperCase());
        credential.setCredentialEnc(encrypt(request.credential()));
        credential.setCreatedBy(userId);
        credential.setUpdatedBy(userId);
        PushCredential saved = pushCredentialRepository.insert(credential);
        return toDto(saved);
    }

    @Transactional
    public PushCredentialDto update(Long userId, Long id, PushCredentialUpdateRequest request) {
        PushCredential credential = requireOwnedByUser(id, userId);
        credential.setName(request.name().trim());
        credential.setPlatform(request.platform().trim().toUpperCase());
        if (request.credential() != null && !request.credential().isBlank()) {
            credential.setCredentialEnc(encrypt(request.credential()));
        }
        credential.setUpdatedBy(userId);
        PushCredential saved = pushCredentialRepository.update(credential);
        return toDto(saved);
    }

    @Transactional
    public void delete(Long userId, Long id) {
        PushCredential credential = requireOwnedByUser(id, userId);
        pushCredentialRepository.softDeleteById(credential.getId(), userId);
    }

    @Transactional(readOnly = true)
    public String getDecryptedCredential(Long userId, Long id) {
        PushCredential credential = pushCredentialRepository.findByIdAndUserId(id, userId).orElse(null);
        if (credential == null || credential.getCredentialEnc() == null) {
            return null;
        }
        return credentialEncryptor.decrypt(credential.getCredentialEnc());
    }

    private String encrypt(String credential) {
        if (credential == null || credential.isBlank()) {
            return null;
        }
        return credentialEncryptor.encrypt(credential.trim());
    }

    private PushCredentialDto toDto(PushCredential credential) {
        return new PushCredentialDto(
            credential.getId(),
            credential.getName(),
            credential.getPlatform(),
            credential.getCredentialEnc() != null && !credential.getCredentialEnc().isBlank()
        );
    }
}
