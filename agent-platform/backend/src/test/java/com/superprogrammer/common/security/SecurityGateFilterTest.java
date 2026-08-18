// agent-platform/backend/src/test/java/com/superprogrammer/common/security/SecurityGateFilterTest.java
package com.superprogrammer.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.common.metrics.BizMetrics;
import com.superprogrammer.common.ratelimit.RateLimiter;
import com.superprogrammer.system.service.SystemSettingService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * SecurityGateFilter 单测（11x P2-C5）：黑名单 403 / 全局限流 429 / 注入 403+事件 / 放行 / 切片降级。
 */
@ExtendWith(MockitoExtension.class)
class SecurityGateFilterTest {

    @Mock private IpBlacklistService ipBlacklistService;
    @Mock private RateLimiter rateLimiter;
    @Mock private SecurityEventService securityEventService;
    @Mock private SystemSettingService systemSettingService;
    @Mock private ClientIpResolver clientIpResolver;
    @Mock private BizMetrics bizMetrics;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private FilterChain filterChain;

    @Mock private ObjectProvider<IpBlacklistService> ipBlacklistProvider;
    @Mock private ObjectProvider<RateLimiter> rateLimiterProvider;
    @Mock private ObjectProvider<SecurityEventService> eventServiceProvider;
    @Mock private ObjectProvider<SystemSettingService> settingsProvider;
    @Mock private ObjectProvider<ClientIpResolver> ipResolverProvider;
    @Mock private ObjectProvider<BizMetrics> metricsProvider;
    @Mock private ObjectProvider<StringRedisTemplate> redisProvider;

    private SecurityGateFilter filter;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        lenient().when(ipBlacklistProvider.getIfAvailable()).thenReturn(ipBlacklistService);
        lenient().when(rateLimiterProvider.getIfAvailable()).thenReturn(rateLimiter);
        lenient().when(eventServiceProvider.getIfAvailable()).thenReturn(securityEventService);
        lenient().when(settingsProvider.getIfAvailable()).thenReturn(systemSettingService);
        lenient().when(ipResolverProvider.getIfAvailable()).thenReturn(clientIpResolver);
        lenient().when(metricsProvider.getIfAvailable()).thenReturn(bizMetrics);
        lenient().when(redisProvider.getIfAvailable()).thenReturn(redisTemplate);
        lenient().when(clientIpResolver.resolve(any())).thenReturn("1.2.3.4");
        lenient().when(systemSettingService.getLong(SecurityGateFilter.KEY_GLOBAL_IP_MAX, 600L)).thenReturn(600L);
        filter = new SecurityGateFilter(ipBlacklistProvider, rateLimiterProvider, eventServiceProvider,
                settingsProvider, ipResolverProvider, metricsProvider, redisProvider, new ObjectMapper());
        request = new MockHttpServletRequest();
        request.setRequestURI("/api/agents");
        response = new MockHttpServletResponse();
    }

    @Test
    void blacklistedIp_403AndEvent() throws Exception {
        when(ipBlacklistService.isBlocked("1.2.3.4")).thenReturn(true);

        filter.doFilterInternal(request, response, filterChain);

        assertEquals(403, response.getStatus());
        verify(securityEventService).record(eq(SecurityEventTypes.IP_BLOCKED_HIT), eq(SecurityEventTypes.SEV_HIGH),
                isNull(), eq("1.2.3.4"), isNull(), anyString(), eq(SecurityEventTypes.ACT_NONE));
        verifyNoInteractions(filterChain);
    }

    @Test
    void globalRateExceeded_429AndBurstEvent() throws Exception {
        when(ipBlacklistService.isBlocked("1.2.3.4")).thenReturn(false);
        when(rateLimiter.checkFixed("rl:global:1.2.3.4", 600L, 60L)).thenReturn(false);

        filter.doFilterInternal(request, response, filterChain);

        assertEquals(429, response.getStatus());
        verify(bizMetrics).apiRateLimited("global_ip");
        verify(securityEventService).record(eq(SecurityEventTypes.RATE_BURST), eq(SecurityEventTypes.SEV_MEDIUM),
                isNull(), eq("1.2.3.4"), eq("global_ip"), anyString(), eq(SecurityEventTypes.ACT_NONE));
        verifyNoInteractions(filterChain);
    }

    @Test
    void injectionInQuery_403AndEvent() throws Exception {
        when(ipBlacklistService.isBlocked("1.2.3.4")).thenReturn(false);
        when(rateLimiter.checkFixed("rl:global:1.2.3.4", 600L, 60L)).thenReturn(true);
        request.setQueryString("u=' OR 1=1--");
        when(redisTemplate.opsForValue()).thenReturn(mock(
                org.springframework.data.redis.core.ValueOperations.class));

        filter.doFilterInternal(request, response, filterChain);

        assertEquals(403, response.getStatus());
        verify(securityEventService).record(eq(SecurityEventTypes.SQLI_PROBE), eq(SecurityEventTypes.SEV_HIGH),
                isNull(), eq("1.2.3.4"), isNull(), contains("snippet"), eq(SecurityEventTypes.ACT_NONE));
        verifyNoInteractions(filterChain);
    }

    @Test
    void adminRequest_bypassesGlobalRateLimit() throws Exception {
        // 13x：admin 豁免——全局限流必拒也放行（不触达 Redis 计数；黑名单/注入扫描照常走）
        when(ipBlacklistService.isBlocked("1.2.3.4")).thenReturn(false);
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        1L, "admin",
                        java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_admin"))));

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verifyNoInteractions(rateLimiter);
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    @Test
    void normalRequest_passes() throws Exception {
        when(ipBlacklistService.isBlocked("1.2.3.4")).thenReturn(false);
        when(rateLimiter.checkFixed("rl:global:1.2.3.4", 600L, 60L)).thenReturn(true);
        request.setQueryString("keyword=agent");

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verifyNoInteractions(securityEventService);
    }

    @Test
    void nonApiPath_passesWithoutChecks() throws Exception {
        request.setRequestURI("/ws/chat");

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verifyNoInteractions(ipBlacklistService, rateLimiter, securityEventService);
    }

    @Test
    void sliceMissingBeans_passesThrough() throws Exception {
        // @WebMvcTest 切片：全部 provider 返 null → 门整体跳过不崩
        SecurityGateFilter sliceFilter = new SecurityGateFilter(
                emptyProvider(), emptyProvider(), emptyProvider(), emptyProvider(),
                emptyProvider(), emptyProvider(), emptyProvider(), new ObjectMapper());

        sliceFilter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> emptyProvider() {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        lenient().when(p.getIfAvailable()).thenReturn(null);
        return p;
    }
}
