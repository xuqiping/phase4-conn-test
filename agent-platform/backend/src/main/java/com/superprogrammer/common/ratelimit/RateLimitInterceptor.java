package com.superprogrammer.common.ratelimit;

import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.common.metrics.BizMetrics;
import com.superprogrammer.common.security.ClientIpResolver;
import com.superprogrammer.system.service.SystemSettingService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 通用限流拦截器（11x 加固 · P1-C2）：按方法注解 {@link RateLimit} 限流。
 *
 * <p><b>只拦有注解的方法</b>——无注解一律放行（注解即开关，逐端点显式声明）。
 * 挂在 WebMvcConfig 的 /api/** 上，在 SecurityConfig 过滤器链之后执行（SecurityContext 已就绪）。</p>
 *
 * <p>key 形态：{@code rl:u:{userId}:{action}}（已登录）或 {@code rl:ip:{ip}:{action}}（未登录/IP 维度）。</p>
 */
@Slf4j
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    /** 总闸：system_settings 键。false=全部放行（误伤时后台一键关，不重启）。 */
    public static final String KEY_ENABLED = "security.rate.enabled";
    /** 阈值覆盖键前缀：security.rate.{action}.max。 */
    public static final String KEY_MAX_PREFIX = "security.rate.";

    private static final String REDIS_PREFIX = "rl:";

    /**
     * 全部 ObjectProvider 延迟取：@WebMvcTest 切片自动包含 HandlerInterceptor Bean，
     * 但不加载 RateLimiter/SystemSettingService 等普通 @Component——构造期强依赖会让
     * 全部 web 切片测试崩上下文。生产全量扫描必有全部 bean，preHandle 时正常解析。
     */
    private final ObjectProvider<RateLimiter> rateLimiterProvider;
    private final ObjectProvider<SystemSettingService> systemSettingServiceProvider;
    private final ObjectProvider<ClientIpResolver> clientIpResolverProvider;
    private final ObjectProvider<BizMetrics> bizMetricsProvider;
    /** P2-C7 RATE_BURST 升级链（bean 缺失→跳过升级，不影响限流本体）。 */
    private final ObjectProvider<org.springframework.data.redis.core.StringRedisTemplate> redisProvider;
    private final ObjectProvider<com.superprogrammer.common.security.SecurityEventService> securityEventServiceProvider;
    private final ObjectProvider<com.superprogrammer.common.security.IpBlacklistService> ipBlacklistServiceProvider;

    public RateLimitInterceptor(ObjectProvider<RateLimiter> rateLimiterProvider,
                                ObjectProvider<SystemSettingService> systemSettingServiceProvider,
                                ObjectProvider<ClientIpResolver> clientIpResolverProvider,
                                ObjectProvider<BizMetrics> bizMetricsProvider,
                                ObjectProvider<org.springframework.data.redis.core.StringRedisTemplate> redisProvider,
                                ObjectProvider<com.superprogrammer.common.security.SecurityEventService> securityEventServiceProvider,
                                ObjectProvider<com.superprogrammer.common.security.IpBlacklistService> ipBlacklistServiceProvider) {
        this.rateLimiterProvider = rateLimiterProvider;
        this.systemSettingServiceProvider = systemSettingServiceProvider;
        this.clientIpResolverProvider = clientIpResolverProvider;
        this.bizMetricsProvider = bizMetricsProvider;
        this.redisProvider = redisProvider;
        this.securityEventServiceProvider = securityEventServiceProvider;
        this.ipBlacklistServiceProvider = ipBlacklistServiceProvider;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        RateLimit rateLimit = handlerMethod.getMethodAnnotation(RateLimit.class);
        if (rateLimit == null) {
            return true; // 无注解=声明不限流
        }
        RateLimiter rateLimiter = rateLimiterProvider.getIfAvailable();
        if (rateLimiter == null) {
            return true; // 仅 web 切片测试场景到达；生产 bean 必在
        }
        if (!isEnabled()) {
            return true; // 总闸关=全部放行
        }

        String action = rateLimit.action();
        long max = resolveMax(action, rateLimit.max());
        long windowSeconds = rateLimit.windowSeconds();
        String key = REDIS_PREFIX + dimensionKey(request, rateLimit) + ":" + action;

        boolean allowed = rateLimit.algo() == RateLimit.RateLimitAlgo.SLIDING
                ? rateLimiter.checkSliding(key, max, windowSeconds)
                : rateLimiter.checkFixed(key, max, windowSeconds);

        if (!allowed) {
            BizMetrics bizMetrics = bizMetricsProvider.getIfAvailable();
            if (bizMetrics != null) {
                bizMetrics.apiRateLimited(action);
            }
            log.warn("限流触发 action={} key={} max={}/{}s uri={}",
                    action, key, max, windowSeconds, request.getRequestURI());
            escalateRateBurst(key, action);
            throw new BusinessException(ErrorCode.RATE_LIMIT);
        }
        return true;
    }

    /**
     * RATE_BURST 屡犯升级（11x P2-C7）：429 时按维度累计（rlburst:{u:42|ip:x}，TTL 1h）；
     * ≥3 次 → RATE_BURST MEDIUM 事件；≥10 次且 IP 维度 → 自动封 IP 30min。
     * 全程 try 吞——升级链故障不影响 429 本体。
     */
    private void escalateRateBurst(String rateKey, String action) {
        try {
            var redis = redisProvider.getIfAvailable();
            if (redis == null) {
                return;
            }
            // rateKey 形态 rl:u:42:action / rl:ip:1.2.3.4:action → 维度 u:42 / ip:1.2.3.4（去 action 聚合）
            String dimension = rateKey.substring(REDIS_PREFIX.length());
            dimension = dimension.substring(0, dimension.lastIndexOf(':'));
            String burstKey = "rlburst:" + dimension;
            Long hits = redis.opsForValue().increment(burstKey);
            if (hits == null) {
                return;
            }
            if (hits == 1L) {
                redis.expire(burstKey, 1, java.util.concurrent.TimeUnit.HOURS);
            }
            if (hits == 3L) {
                var eventService = securityEventServiceProvider.getIfAvailable();
                if (eventService != null) {
                    eventService.record(com.superprogrammer.common.security.SecurityEventTypes.RATE_BURST,
                            com.superprogrammer.common.security.SecurityEventTypes.SEV_MEDIUM,
                            null, dimension.startsWith("ip:") ? dimension.substring(3) : null,
                            "rate_burst", "{\"dimension\":\"" + dimension + "\",\"action\":\"" + action + "\"}",
                            com.superprogrammer.common.security.SecurityEventTypes.ACT_NONE);
                }
            }
            if (hits >= 10L && dimension.startsWith("ip:")) {
                var ipBlacklist = ipBlacklistServiceProvider.getIfAvailable();
                if (ipBlacklist != null) {
                    ipBlacklist.autoBlock(dimension.substring(3),
                            com.superprogrammer.common.security.SecurityEventTypes.RATE_BURST, 30);
                }
            }
        } catch (Exception e) {
            log.warn("RATE_BURST 升级链失败(已吞) key={} : {}", rateKey, e.getMessage());
        }
    }

    /** 总闸读取：bean 缺失（切片）→ 开；DB 异常 → 放行（限流不可用 > 不可用，与 RateLimiter 降级一致）。 */
    private boolean isEnabled() {
        SystemSettingService systemSettingService = systemSettingServiceProvider.getIfAvailable();
        if (systemSettingService == null) {
            return true;
        }
        try {
            return systemSettingService.getBoolean(KEY_ENABLED, true);
        } catch (Exception e) {
            log.warn("限流总闸读取失败(降级放行): {}", e.getMessage());
            return true;
        }
    }

    /** 阈值解析：system_settings 覆盖 > 注解默认。bean 缺失/DB 异常 → 注解默认。 */
    private long resolveMax(String action, long defaultMax) {
        SystemSettingService systemSettingService = systemSettingServiceProvider.getIfAvailable();
        if (systemSettingService == null) {
            return defaultMax;
        }
        try {
            return systemSettingService.getLong(KEY_MAX_PREFIX + action + ".max", defaultMax);
        } catch (Exception e) {
            return defaultMax;
        }
    }

    /** 维度键：USER=优先 userId，未登录回落 IP；IP=恒 IP。 */
    private String dimensionKey(HttpServletRequest request, RateLimit rateLimit) {
        if (rateLimit.scope() == RateLimit.RateLimitScope.USER) {
            Long userId = currentUserId();
            if (userId != null) {
                return "u:" + userId;
            }
        }
        ClientIpResolver clientIpResolver = clientIpResolverProvider.getIfAvailable();
        String ip = clientIpResolver != null ? clientIpResolver.resolve(request) : request.getRemoteAddr();
        return "ip:" + ip;
    }

    /** 从 SecurityContext 取 userId（JwtAuthenticationFilter 已在链上执行，principal=userId）。 */
    private Long currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof Long userId) {
            return userId;
        }
        return null;
    }
}
