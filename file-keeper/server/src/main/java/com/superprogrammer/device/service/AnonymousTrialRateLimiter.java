package com.superprogrammer.device.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@RequiredArgsConstructor
public class AnonymousTrialRateLimiter {

    private final StringRedisTemplate redisTemplate;

    // 同一 IP 每天最多注册 5 个匿名设备
    private static final int MAX_STARTS_PER_IP_PER_DAY = 5;
    // 同一指纹每天最多注册 3 次
    private static final int MAX_STARTS_PER_FINGERPRINT_PER_DAY = 3;

    public boolean allowStartByIp(String ip) {
        return incrementAndCheck(key("anon:start:ip:", ip), MAX_STARTS_PER_IP_PER_DAY, Duration.ofDays(1));
    }

    public boolean allowStartByFingerprint(String fingerprintHash) {
        return incrementAndCheck(key("anon:start:fp:", fingerprintHash), MAX_STARTS_PER_FINGERPRINT_PER_DAY, Duration.ofDays(1));
    }

    public void recordStart(String ip, String fingerprintHash) {
        // 已经由 allowStartBy* 递增过计数，这里不需要额外操作。
        // 保留该方法用于未来扩展（如记录更细维度）。
    }

    private boolean incrementAndCheck(String key, int maxAllowed, Duration ttl) {
        Long current = redisTemplate.opsForValue().increment(key);
        if (current == null) {
            return false;
        }
        if (current == 1) {
            redisTemplate.expire(key, ttl);
        }
        return current <= maxAllowed;
    }

    private String key(String prefix, String value) {
        return prefix + value;
    }
}
