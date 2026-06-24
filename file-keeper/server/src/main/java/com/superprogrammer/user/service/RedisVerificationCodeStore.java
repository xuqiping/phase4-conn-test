package com.superprogrammer.user.service;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@Profile("!test")
@RequiredArgsConstructor
public class RedisVerificationCodeStore implements VerificationCodeStore {

    private final StringRedisTemplate redisTemplate;

    @Override
    public void saveCode(String contactType, String contact, String code, Duration ttl) {
        redisTemplate.opsForValue().set(codeKey(contactType, contact), code, ttl);
    }

    @Override
    public boolean matchesCode(String contactType, String contact, String code) {
        return code.equals(redisTemplate.opsForValue().get(codeKey(contactType, contact)));
    }

    @Override
    public void markVerified(String contactType, String contact, Duration ttl) {
        redisTemplate.opsForValue().set(verifiedKey(contactType, contact), "1", ttl);
    }

    @Override
    public boolean consumeVerified(String contactType, String contact) {
        String key = verifiedKey(contactType, contact);
        Boolean exists = redisTemplate.hasKey(key);
        if (Boolean.TRUE.equals(exists)) {
            redisTemplate.delete(key);
            redisTemplate.delete(codeKey(contactType, contact));
            return true;
        }
        return false;
    }

    private String codeKey(String contactType, String contact) {
        return "fk:verification:code:" + contactType + ":" + contact;
    }

    private String verifiedKey(String contactType, String contact) {
        return "fk:verification:verified:" + contactType + ":" + contact;
    }
}
