package com.superprogrammer.security;

import com.superprogrammer.common.BusinessException;
import com.superprogrammer.common.ErrorCode;
import com.superprogrammer.config.AuthProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;

@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private final AuthProperties authProperties;
    private final RefreshTokenStore refreshTokenStore;
    private final SecureRandom secureRandom = new SecureRandom();

    public String create(Long userId) {
        byte[] randomBytes = new byte[48];
        secureRandom.nextBytes(randomBytes);
        String token = HexFormat.of().formatHex(randomBytes);
        String hash = hash(token);
        Duration ttl = Duration.ofDays(authProperties.getRefreshToken().getDays());
        refreshTokenStore.save(hash, userId, ttl);
        refreshTokenStore.addTokenToUser(userId, hash, ttl);
        return token;
    }

    public Long requireUserId(String refreshToken) {
        return refreshTokenStore.findUserId(hash(refreshToken))
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED, "refresh token 无效或已过期"));
    }

    public void delete(String refreshToken) {
        refreshTokenStore.delete(hash(refreshToken));
    }

    public void deleteAllForUser(Long userId) {
        refreshTokenStore.deleteAllForUser(userId);
    }

    private String hash(String refreshToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(refreshToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
