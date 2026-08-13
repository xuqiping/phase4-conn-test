package com.superprogrammer.common.ratelimit;

import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.common.metrics.BizMetrics;
import com.superprogrammer.common.security.ClientIpResolver;
import com.superprogrammer.system.service.SystemSettingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * RateLimitInterceptor 单测（11x P1-C2）：注解门控/总闸/阈值覆盖/维度 key/降级。
 * 构造走 ObjectProvider（生产对齐：切片场景 bean 可缺失，本组测试全部注入）。
 */
@ExtendWith(MockitoExtension.class)
class RateLimitInterceptorTest {

    @Mock
    private RateLimiter rateLimiter;
    @Mock
    private SystemSettingService systemSettingService;
    @Mock
    private ClientIpResolver clientIpResolver;
    @Mock
    private BizMetrics bizMetrics;
    @Mock
    private ObjectProvider<RateLimiter> rateLimiterProvider;
    @Mock
    private ObjectProvider<SystemSettingService> systemSettingServiceProvider;
    @Mock
    private ObjectProvider<ClientIpResolver> clientIpResolverProvider;
    @Mock
    private ObjectProvider<BizMetrics> bizMetricsProvider;
    @Mock
    private ObjectProvider<org.springframework.data.redis.core.StringRedisTemplate> redisProvider;
    @Mock
    private ObjectProvider<com.superprogrammer.common.security.SecurityEventService> securityEventServiceProvider;
    @Mock
    private ObjectProvider<com.superprogrammer.common.security.IpBlacklistService> ipBlacklistServiceProvider;
    @Mock
    private HandlerMethod handlerMethod;

    private RateLimitInterceptor interceptor;
    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        // lenient：部分用例（未注解/总闸关）不触达全部 provider；RATE_BURST 升级链 bean 缺失=跳过
        lenient().when(rateLimiterProvider.getIfAvailable()).thenReturn(rateLimiter);
        lenient().when(systemSettingServiceProvider.getIfAvailable()).thenReturn(systemSettingService);
        lenient().when(clientIpResolverProvider.getIfAvailable()).thenReturn(clientIpResolver);
        lenient().when(bizMetricsProvider.getIfAvailable()).thenReturn(bizMetrics);
        lenient().when(redisProvider.getIfAvailable()).thenReturn(null);
        lenient().when(securityEventServiceProvider.getIfAvailable()).thenReturn(null);
        lenient().when(ipBlacklistServiceProvider.getIfAvailable()).thenReturn(null);
        interceptor = new RateLimitInterceptor(rateLimiterProvider, systemSettingServiceProvider,
                clientIpResolverProvider, bizMetricsProvider, redisProvider,
                securityEventServiceProvider, ipBlacklistServiceProvider);
        request = new MockHttpServletRequest();
        request.setRequestURI("/api/test");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void nonHandlerMethod_passes() {
        assertTrue(interceptor.preHandle(request, null, new Object()));
    }

    @Test
    void noAnnotation_passes() {
        when(handlerMethod.getMethodAnnotation(RateLimit.class)).thenReturn(null);
        assertTrue(interceptor.preHandle(request, null, handlerMethod));
        verifyNoInteractions(rateLimiter);
    }

    @Test
    void masterSwitchOff_passes() {
        RateLimit ann = annotation("chat_send", 20, 60);
        when(handlerMethod.getMethodAnnotation(RateLimit.class)).thenReturn(ann);
        when(systemSettingService.getBoolean(RateLimitInterceptor.KEY_ENABLED, true)).thenReturn(false);

        assertTrue(interceptor.preHandle(request, null, handlerMethod));
        verifyNoInteractions(rateLimiter);
    }

    @Test
    void annotated_underLimit_passes_withUserKey() {
        RateLimit ann = annotation("chat_send", 20, 60);
        when(handlerMethod.getMethodAnnotation(RateLimit.class)).thenReturn(ann);
        when(systemSettingService.getBoolean(RateLimitInterceptor.KEY_ENABLED, true)).thenReturn(true);
        when(systemSettingService.getLong("security.rate.chat_send.max", 20L)).thenReturn(20L);
        when(rateLimiter.checkFixed("rl:u:42:chat_send", 20, 60)).thenReturn(true);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(42L, "alice", java.util.List.of()));

        assertTrue(interceptor.preHandle(request, null, handlerMethod));
        verify(rateLimiter).checkFixed("rl:u:42:chat_send", 20, 60);
    }

    @Test
    void annotated_overLimit_throws429() {
        RateLimit ann = annotation("chat_send", 20, 60);
        when(handlerMethod.getMethodAnnotation(RateLimit.class)).thenReturn(ann);
        when(systemSettingService.getBoolean(RateLimitInterceptor.KEY_ENABLED, true)).thenReturn(true);
        when(systemSettingService.getLong("security.rate.chat_send.max", 20L)).thenReturn(20L);
        when(rateLimiter.checkFixed(anyString(), eq(20L), eq(60L))).thenReturn(false);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(42L, "alice", java.util.List.of()));

        BusinessException e = assertThrows(BusinessException.class,
                () -> interceptor.preHandle(request, null, handlerMethod));
        assertEquals(ErrorCode.RATE_LIMIT.getCode(), e.getCode());
        verify(bizMetrics).apiRateLimited("chat_send");
    }

    @Test
    void unauthenticated_fallsBackToIpKey() {
        RateLimit ann = annotation("export", 10, 60);
        when(handlerMethod.getMethodAnnotation(RateLimit.class)).thenReturn(ann);
        when(systemSettingService.getBoolean(RateLimitInterceptor.KEY_ENABLED, true)).thenReturn(true);
        when(systemSettingService.getLong("security.rate.export.max", 10L)).thenReturn(10L);
        when(clientIpResolver.resolve(request)).thenReturn("1.2.3.4");
        when(rateLimiter.checkFixed("rl:ip:1.2.3.4:export", 10, 60)).thenReturn(true);

        assertTrue(interceptor.preHandle(request, null, handlerMethod));
        verify(rateLimiter).checkFixed("rl:ip:1.2.3.4:export", 10, 60);
    }

    @Test
    void settingsOverrideMax_applies() {
        RateLimit ann = annotation("media_submit", 5, 60);
        when(handlerMethod.getMethodAnnotation(RateLimit.class)).thenReturn(ann);
        when(systemSettingService.getBoolean(RateLimitInterceptor.KEY_ENABLED, true)).thenReturn(true);
        when(systemSettingService.getLong("security.rate.media_submit.max", 5L)).thenReturn(2L); // 后台热调
        when(rateLimiter.checkFixed(anyString(), eq(2L), eq(60L))).thenReturn(true);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(7L, "bob", java.util.List.of()));

        assertTrue(interceptor.preHandle(request, null, handlerMethod));
        verify(rateLimiter).checkFixed("rl:u:7:media_submit", 2, 60);
    }

    @Test
    void settingsDbDown_failsOpen() {
        RateLimit ann = annotation("chat_send", 20, 60);
        when(handlerMethod.getMethodAnnotation(RateLimit.class)).thenReturn(ann);
        when(systemSettingService.getBoolean(RateLimitInterceptor.KEY_ENABLED, true))
                .thenThrow(new RuntimeException("db down"));
        // DB 挂=所有 settings 读都炸：阈值读降级注解默认 20，限流判定仍走 Redis（limiter 自身降级放行）
        when(systemSettingService.getLong("security.rate.chat_send.max", 20L))
                .thenThrow(new RuntimeException("db down"));
        when(rateLimiter.checkFixed("rl:ip:10.0.0.1:chat_send", 20, 60)).thenReturn(true);
        when(clientIpResolver.resolve(request)).thenReturn("10.0.0.1");

        assertTrue(interceptor.preHandle(request, null, handlerMethod));
        verify(rateLimiter).checkFixed("rl:ip:10.0.0.1:chat_send", 20, 60);
    }

    @Test
    void slidingAlgo_dispatchesSliding() {
        RateLimit ann = mock(RateLimit.class);
        when(ann.action()).thenReturn("chat_send");
        when(ann.max()).thenReturn(20);
        when(ann.windowSeconds()).thenReturn(60);
        when(ann.algo()).thenReturn(RateLimit.RateLimitAlgo.SLIDING);
        when(ann.scope()).thenReturn(RateLimit.RateLimitScope.USER);
        when(handlerMethod.getMethodAnnotation(RateLimit.class)).thenReturn(ann);
        when(systemSettingService.getBoolean(RateLimitInterceptor.KEY_ENABLED, true)).thenReturn(true);
        when(systemSettingService.getLong("security.rate.chat_send.max", 20L)).thenReturn(20L);
        when(rateLimiter.checkSliding(anyString(), eq(20L), eq(60L))).thenReturn(true);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(42L, "alice", java.util.List.of()));

        assertTrue(interceptor.preHandle(request, null, handlerMethod));
        verify(rateLimiter).checkSliding("rl:u:42:chat_send", 20, 60);
        verify(rateLimiter, never()).checkFixed(anyString(), anyLong(), anyLong());
    }

    /** 构造注解实例（mock 轻量替代实现接口）。lenient：总闸关等短路场景不读注解字段。 */
    private RateLimit annotation(String action, int max, int window) {
        RateLimit ann = mock(RateLimit.class);
        lenient().when(ann.action()).thenReturn(action);
        lenient().when(ann.max()).thenReturn(max);
        lenient().when(ann.windowSeconds()).thenReturn(window);
        lenient().when(ann.algo()).thenReturn(RateLimit.RateLimitAlgo.FIXED);
        lenient().when(ann.scope()).thenReturn(RateLimit.RateLimitScope.USER);
        return ann;
    }
}
