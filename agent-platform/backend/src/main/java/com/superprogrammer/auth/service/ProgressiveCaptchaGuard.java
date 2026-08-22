// agent-platform/backend/src/main/java/com/superprogrammer/auth/service/ProgressiveCaptchaGuard.java
package com.superprogrammer.auth.service;

import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 12x B2：渐进式滑块门槛——同一 key 连续失败 ≥2 次后，该 key 后续请求必须带滑块 token。
 *
 * <p>适用端点：登录（key=用户名）、注册/找回密码/注册发码（key=客户端 IP）。
 * 设计语义：
 * <ul>
 *   <li>计数键 30min 窗（自然过期回归无验证态）；成功一次即清零；</li>
 *   <li>滑块 token 单次有效由 {@link CaptchaService}（AJ-Captcha 二次校验删 key）兜底；</li>
 *   <li>Redis 故障 → 降级放行（不阻断认证主链，与登录防爆破同策略）；</li>
 *   <li>门槛触发但前端未带 token → CAPTCHA_INVALID +「请先完成滑块验证」话术（前端凭此弹滑块）。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProgressiveCaptchaGuard {

    private final StringRedisTemplate redisTemplate;
    private final CaptchaService captchaService;

    private static final String PREFIX = "captcha:need:";
    private static final long WINDOW_SECONDS = 30 * 60;
    private static final long THRESHOLD = 2;

    /**
     * 门槛检查：key 连续失败 ≥2 次 → 必须带有效滑块 token，否则抛 CAPTCHA_INVALID。
     * 带 token 但校验不过 → CaptchaService 抛 CAPTCHA_INVALID（计入失败由调用方决定）。
     */
    public void check(String scope, String key, String captchaVerification) {
        if (!isRequired(scope, key)) {
            return;
        }
        if (captchaVerification == null || captchaVerification.isBlank()) {
            throw new BusinessException(ErrorCode.CAPTCHA_INVALID, "请先完成滑块验证");
        }
        captchaService.verify(captchaVerification);
    }

    /** 当前 key 是否已触发滑块门槛（失败计数 ≥2）。 */
    public boolean isRequired(String scope, String key) {
        String k = normalize(scope, key);
        if (k == null) {
            return false;
        }
        try {
            String v = redisTemplate.opsForValue().get(k);
            return v != null && Long.parseLong(v) >= THRESHOLD;
        } catch (Exception e) {
            log.warn("滑块门槛查询失败(降级放行) : {}", e.toString());
            return false;
        }
    }

    /** 记一次失败（首次创建计数键挂 30min TTL）。 */
    public void recordFailure(String scope, String key) {
        String k = normalize(scope, key);
        if (k == null) {
            return;
        }
        try {
            Long n = redisTemplate.opsForValue().increment(k);
            if (n != null && n == 1L) {
                redisTemplate.expire(k, WINDOW_SECONDS, TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            log.warn("滑块失败计数失败(已吞) : {}", e.toString());
        }
    }

    /** 成功清零（回归无验证态）。 */
    public void clear(String scope, String key) {
        String k = normalize(scope, key);
        if (k == null) {
            return;
        }
        try {
            redisTemplate.delete(k);
        } catch (Exception e) {
            log.warn("滑块门槛清零失败(已吞) : {}", e.toString());
        }
    }

    private String normalize(String scope, String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        return PREFIX + scope + ":" + key.trim().toLowerCase(Locale.ROOT);
    }
}
