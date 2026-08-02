package com.superprogrammer.user.service;

import com.superprogrammer.common.BusinessException;
import com.superprogrammer.common.ErrorCode;
import com.superprogrammer.config.AuthProperties;
import com.superprogrammer.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.time.Duration;

@Service
@RequiredArgsConstructor
public class VerificationService {

    private final AuthProperties authProperties;
    private final VerificationCodeStore verificationCodeStore;
    private final UserRepository userRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    public void send(String contactType, String contact) {
        String normalizedType = normalizeContactType(contactType);
        String normalizedContact = normalizeContact(normalizedType, contact);
        if (userRepository.existsByContact(normalizedType, normalizedContact)) {
            throw new BusinessException(ErrorCode.CONFLICT, "联系方式已注册");
        }
        verificationCodeStore.saveCode(
                normalizedType,
                normalizedContact,
                generateCode(),
                Duration.ofMinutes(authProperties.getVerification().getCodeMinutes())
        );
    }

    public boolean check(String contactType, String contact, String code) {
        String normalizedType = normalizeContactType(contactType);
        String normalizedContact = normalizeContact(normalizedType, contact);
        if (!verificationCodeStore.matchesCode(normalizedType, normalizedContact, code)) {
            throw new BusinessException(ErrorCode.UNPROCESSABLE, "验证码错误或已过期");
        }
        verificationCodeStore.markVerified(
                normalizedType,
                normalizedContact,
                Duration.ofMinutes(authProperties.getVerification().getVerifiedMinutes())
        );
        return true;
    }

    public void consumeVerified(String contactType, String contact) {
        String normalizedType = normalizeContactType(contactType);
        String normalizedContact = normalizeContact(normalizedType, contact);
        if (!verificationCodeStore.consumeVerified(normalizedType, normalizedContact)) {
            throw new BusinessException(ErrorCode.UNPROCESSABLE, "请先完成联系方式验证");
        }
    }

    public String normalizeContactType(String contactType) {
        if (!"email".equals(contactType) && !"phone".equals(contactType)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "联系方式类型必须是 email 或 phone");
        }
        return contactType;
    }

    public String normalizeContact(String contactType, String contact) {
        if (!StringUtils.hasText(contact)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "联系方式不能为空");
        }
        String normalized = contact.trim();
        return "email".equals(contactType) ? normalized.toLowerCase() : normalized;
    }

    private String generateCode() {
        String fixedCode = authProperties.getVerification().getDevFixedCode();
        if (StringUtils.hasText(fixedCode)) {
            return fixedCode;
        }
        return String.format("%06d", secureRandom.nextInt(1_000_000));
    }
}
