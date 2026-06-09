package com.superprogrammer.security;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;

@Component
@Profile("!test")
@RequiredArgsConstructor
public class RedisRefreshTokenStore implements RefreshTokenStore {

    private final StringRedisTemplate redisTemplate;

    @Override
    public void save(String tokenHash, Long userId, Duration ttl) {
        redisTemplate.opsForValue().set(tokenKey(tokenHash), String.valueOf(userId), ttl);
    }

    @Override
    public Optional<Long> findUserId(String tokenHash) {
        String value = redisTemplate.opsForValue().get(tokenKey(tokenHash));
        return value == null ? Optional.empty() : Optional.of(Long.valueOf(value));
    }

    @Override
    public void delete(String tokenHash) {
        redisTemplate.delete(tokenKey(tokenHash));
    }

    @Override
    public void addTokenToUser(Long userId, String tokenHash, Duration ttl) {
        String key = userTokensKey(userId);
        redisTemplate.opsForSet().add(key, tokenHash);
        redisTemplate.expire(key, ttl);
    }

    @Override
    public void deleteAllForUser(Long userId) {
        String key = userTokensKey(userId);
        Set<String> tokenHashes = redisTemplate.opsForSet().members(key);
        if (tokenHashes != null) {
            tokenHashes.forEach(this::delete);
        }
        redisTemplate.delete(key);
    }

    private String tokenKey(String tokenHash) {
        return "fk:refresh:" + tokenHash;
    }

    private String userTokensKey(Long userId) {
        return "fk:user:" + userId + ":refresh";
    }
}
