// agent-platform/backend/src/main/java/com/superprogrammer/auth/config/CaptchaCacheServiceRedisImpl.java
package com.superprogrammer.auth.config;

import com.anji.captcha.service.CaptchaCacheService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;

/**
 * AJ-Captcha 的 Redis 缓存实现（认证系统增强 Chunk C/F）。
 *
 * <p>为什么需要它：aj-captcha 1.3.0 官方 jar 只内置 local 内存实现
 * （{@code CaptchaCacheServiceMemImpl}，ServiceLoader 自注册），redis 实现留给业务方自写。
 *
 * <p>为什么不用 @Service：注册进 {@code CaptchaServiceFactory.cacheService} 由
 * {@link CaptchaConfig} 显式完成（key 必须是 {@code type()} 返回值 "redis"，小写，
 * 与 {@code AjCaptchaProperties.StorageType} 枚举常量名一致）。
 *
 * <p>安全语义：验证码 token 存 Redis——跨实例共享 + 单次有效（AJ-Captcha 校验通过后
 * DEL key，防重放）。
 */
@RequiredArgsConstructor
public class CaptchaCacheServiceRedisImpl implements CaptchaCacheService {

    private final StringRedisTemplate redisTemplate;

    @Override
    public void set(String key, String value, long expiresInSeconds) {
        redisTemplate.opsForValue().set(key, value, expiresInSeconds, TimeUnit.SECONDS);
    }

    @Override
    public boolean exists(String key) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }

    @Override
    public void delete(String key) {
        redisTemplate.delete(key);
    }

    @Override
    public String get(String key) {
        return redisTemplate.opsForValue().get(key);
    }

    /** 缓存类型标识——必须与 aj.captcha.cache-type 配置值一致（小写 redis）。 */
    @Override
    public String type() {
        return "redis";
    }

    @Override
    public Long increment(String key, long delta) {
        return redisTemplate.opsForValue().increment(key, delta);
    }
}
