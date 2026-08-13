// agent-platform/backend/src/main/java/com/superprogrammer/common/security/SecurityGateFilter.java
package com.superprogrammer.common.security;

import com.superprogrammer.common.metrics.BizMetrics;
import com.superprogrammer.common.ratelimit.RateLimiter;
import com.superprogrammer.system.service.SystemSettingService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 安全门过滤器（11x 加固 · P2-C5）：热路径同步拦截，排在 JwtAuthenticationFilter/MdcUserFilter 之后。
 *
 * <p>顺序（越便宜越前）：
 * <ol>
 *   <li><b>IP 黑名单</b>：Redis hasKey（&lt;1ms），命中 → 403 固定话术（不透传「被封」防探测）；</li>
 *   <li><b>全局 per-IP 限流</b>：固定窗口（默认 600/min，system_settings `security.rate.global_ip.max` 热调），
 *       超 → 429 + RATE_BURST 事件（MEDIUM）；</li>
 *   <li><b>注入特征</b>：只扫 URL query + 表单参数键值（application/x-www-form-urlencoded），
 *       不扫 JSON 正文（chat 消息走 P3 PROMPT_INJECTION 冷规则），命中 → 403 + 事件（HIGH）；
 *       累犯（同 IP 1h ≥3 次）→ 自动封 IP 1h。</li>
 * </ol>
 *
 * <p>依赖全 ObjectProvider 延迟取：@WebMvcTest 切片自动包含 Filter Bean 但不加载普通 @Component，
 * 强依赖会崩全部切片上下文；bean 缺失 → 跳过对应检查（切片友好，生产 bean 必在）。</p>
 */
@Slf4j
@Component
public class SecurityGateFilter extends OncePerRequestFilter {

    /** 全局 per-IP 限流默认阈值：600 次/分钟（正常用户远低于此，爬虫/脚本会撞）。 */
    public static final String KEY_GLOBAL_IP_MAX = "security.rate.global_ip.max";
    private static final long DEFAULT_GLOBAL_IP_MAX = 600;
    /** 注入累犯封禁阈值：同 IP 1h 内命中次数。 */
    private static final long INJECTION_AUTOBLOCK_THRESHOLD = 3;
    private static final long INJECTION_AUTOBLOCK_MINUTES = 60;
    /** 注入累犯计数键前缀。 */
    private static final String INJECTION_HIT_PREFIX = "inj:hit:";

    private final ObjectProvider<IpBlacklistService> ipBlacklistServiceProvider;
    private final ObjectProvider<RateLimiter> rateLimiterProvider;
    private final ObjectProvider<SecurityEventService> securityEventServiceProvider;
    private final ObjectProvider<SystemSettingService> systemSettingServiceProvider;
    private final ObjectProvider<ClientIpResolver> clientIpResolverProvider;
    private final ObjectProvider<BizMetrics> bizMetricsProvider;
    private final ObjectProvider<org.springframework.data.redis.core.StringRedisTemplate> redisProvider;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    public SecurityGateFilter(ObjectProvider<IpBlacklistService> ipBlacklistServiceProvider,
                              ObjectProvider<RateLimiter> rateLimiterProvider,
                              ObjectProvider<SecurityEventService> securityEventServiceProvider,
                              ObjectProvider<SystemSettingService> systemSettingServiceProvider,
                              ObjectProvider<ClientIpResolver> clientIpResolverProvider,
                              ObjectProvider<BizMetrics> bizMetricsProvider,
                              ObjectProvider<org.springframework.data.redis.core.StringRedisTemplate> redisProvider,
                              com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        this.ipBlacklistServiceProvider = ipBlacklistServiceProvider;
        this.rateLimiterProvider = rateLimiterProvider;
        this.securityEventServiceProvider = securityEventServiceProvider;
        this.systemSettingServiceProvider = systemSettingServiceProvider;
        this.clientIpResolverProvider = clientIpResolverProvider;
        this.bizMetricsProvider = bizMetricsProvider;
        this.redisProvider = redisProvider;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!path.startsWith("/api/")) {
            filterChain.doFilter(request, response);
            return; // 只守 API 面（/ws /actuator 各有自家咽喉）
        }

        String ip = resolveIp(request);
        SecurityEventService eventService = securityEventServiceProvider.getIfAvailable();

        // 1. IP 黑名单
        IpBlacklistService ipBlacklistService = ipBlacklistServiceProvider.getIfAvailable();
        if (ipBlacklistService != null && ipBlacklistService.isBlocked(ip)) {
            if (eventService != null) {
                eventService.record(SecurityEventTypes.IP_BLOCKED_HIT, SecurityEventTypes.SEV_HIGH,
                        null, ip, null, "{\"path\":\"" + escapeJson(path) + "\"}",
                        SecurityEventTypes.ACT_NONE);
            }
            log.warn("已封 IP 再次来访(拦截) ip={} uri={}", ip, path);
            writeReject(response, 403);
            return;
        }

        // 2. 全局 per-IP 限流
        RateLimiter rateLimiter = rateLimiterProvider.getIfAvailable();
        if (rateLimiter != null) {
            long max = resolveGlobalMax();
            if (!rateLimiter.checkFixed("rl:global:" + ip, max, 60)) {
                BizMetrics bizMetrics = bizMetricsProvider.getIfAvailable();
                if (bizMetrics != null) {
                    bizMetrics.apiRateLimited("global_ip");
                }
                if (eventService != null) {
                    eventService.record(SecurityEventTypes.RATE_BURST, SecurityEventTypes.SEV_MEDIUM,
                            null, ip, "global_ip", "{\"path\":\"" + escapeJson(path) + "\",\"max\":" + max + "}",
                            SecurityEventTypes.ACT_NONE);
                }
                log.warn("全局限流触发 ip={} max={}/60s uri={}", ip, max, path);
                writeReject(response, 429);
                return;
            }
        }

        // 3. 注入特征（query + 表单参数键值；不扫 JSON 正文）
        InjectionDetector.Hit hit = scanParams(request);
        if (hit != null) {
            if (eventService != null) {
                eventService.record(hit.eventType(), SecurityEventTypes.SEV_HIGH,
                        null, ip, null,
                        "{\"path\":\"" + escapeJson(path) + "\",\"snippet\":\"" + escapeJson(hit.snippet()) + "\"}",
                        SecurityEventTypes.ACT_NONE);
            }
            countInjectionHitAndMaybeBlock(ip, ipBlacklistService);
            log.warn("注入特征命中(拦截) type={} ip={} uri={}", hit.eventType(), ip, path);
            writeReject(response, 403);
            return;
        }

        filterChain.doFilter(request, response);
    }

    /** 参数面扫描：query string + 表单 POST 的键与值逐个过 InjectionDetector。 */
    private InjectionDetector.Hit scanParams(HttpServletRequest request) {
        String query = request.getQueryString();
        if (query != null) {
            InjectionDetector.Hit hit = InjectionDetector.detect(query);
            if (hit != null) {
                return hit;
            }
        }
        String contentType = request.getContentType();
        if (contentType != null && contentType.startsWith(MediaType.APPLICATION_FORM_URLENCODED_VALUE)) {
            for (Map.Entry<String, String[]> entry : request.getParameterMap().entrySet()) {
                InjectionDetector.Hit hit = InjectionDetector.detect(entry.getKey());
                if (hit != null) {
                    return hit;
                }
                for (String value : entry.getValue()) {
                    hit = InjectionDetector.detect(value);
                    if (hit != null) {
                        return hit;
                    }
                }
            }
        }
        return null;
    }

    /** 注入累犯：同 IP 1h 内 ≥3 次 → 自动封 1h。Redis 故障 → 跳过（事件已落库，人工可追）。 */
    private void countInjectionHitAndMaybeBlock(String ip, IpBlacklistService ipBlacklistService) {
        if (ipBlacklistService == null) {
            return;
        }
        var redis = redisProvider.getIfAvailable();
        if (redis == null) {
            return;
        }
        try {
            String key = INJECTION_HIT_PREFIX + ip;
            Long hits = redis.opsForValue().increment(key);
            if (hits != null && hits == 1L) {
                redis.expire(key, 1, TimeUnit.HOURS);
            }
            if (hits != null && hits >= INJECTION_AUTOBLOCK_THRESHOLD) {
                ipBlacklistService.autoBlock(ip, SecurityEventTypes.SQLI_PROBE, INJECTION_AUTOBLOCK_MINUTES);
                SecurityEventService eventService = securityEventServiceProvider.getIfAvailable();
                if (eventService != null) {
                    eventService.record(SecurityEventTypes.SQLI_PROBE, SecurityEventTypes.SEV_HIGH,
                            null, ip, "injection_repeat",
                            "{\"hits\":" + hits + "}", SecurityEventTypes.ACT_IP_BLOCKED);
                }
            }
        } catch (Exception e) {
            log.warn("注入累犯计数失败(已吞) ip={} : {}", ip, e.getMessage());
        }
    }

    private long resolveGlobalMax() {
        SystemSettingService settings = systemSettingServiceProvider.getIfAvailable();
        if (settings == null) {
            return DEFAULT_GLOBAL_IP_MAX;
        }
        try {
            return settings.getLong(KEY_GLOBAL_IP_MAX, DEFAULT_GLOBAL_IP_MAX);
        } catch (Exception e) {
            return DEFAULT_GLOBAL_IP_MAX;
        }
    }

    private String resolveIp(HttpServletRequest request) {
        ClientIpResolver resolver = clientIpResolverProvider.getIfAvailable();
        return resolver != null ? resolver.resolve(request) : request.getRemoteAddr();
    }

    /** 403/429 固定话术（不透传具体原因防探测；R 格式与全局一致）。 */
    private void writeReject(HttpServletResponse response, int status) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        var code = status == 429
                ? com.superprogrammer.common.exception.ErrorCode.RATE_LIMIT
                : com.superprogrammer.common.exception.ErrorCode.FORBIDDEN;
        response.getWriter().write(objectMapper.writeValueAsString(
                com.superprogrammer.common.result.R.fail(code)));
    }

    private static String escapeJson(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
