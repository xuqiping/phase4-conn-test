package com.superprogrammer.common.ratelimit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * RateLimiter 单测（11x P1-C2）：固定/滑动窗口边界 + Redis 故障降级放行。
 */
@ExtendWith(MockitoExtension.class)
class RateLimiterTest {

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOps;
    @Mock
    private ZSetOperations<String, String> zSetOps;

    private RateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        rateLimiter = new RateLimiter(redisTemplate);
    }

    // ---------- 固定窗口 ----------

    @Test
    void fixed_firstHit_setsExpireAndPasses() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment("k")).thenReturn(1L);

        assertTrue(rateLimiter.checkFixed("k", 5, 60));
        verify(valueOps).increment("k");
        verify(redisTemplate).expire("k", 60, TimeUnit.SECONDS);
    }

    @Test
    void fixed_atMax_passes_noDoubleExpire() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment("k")).thenReturn(5L);

        assertTrue(rateLimiter.checkFixed("k", 5, 60));
        verify(redisTemplate, never()).expire(anyString(), anyLong(), any());
    }

    @Test
    void fixed_overMax_rejects() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment("k")).thenReturn(6L);

        assertFalse(rateLimiter.checkFixed("k", 5, 60));
    }

    @Test
    void fixed_nullIncrement_passes() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment("k")).thenReturn(null);

        assertTrue(rateLimiter.checkFixed("k", 5, 60));
    }

    @Test
    void fixed_redisDown_failsOpen() {
        when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("redis down"));

        assertTrue(rateLimiter.checkFixed("k", 5, 60));
    }

    // ---------- 滑动窗口 ----------

    @Test
    void sliding_underMax_addsAndPasses() {
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        when(zSetOps.zCard("k")).thenReturn(2L);

        assertTrue(rateLimiter.checkSliding("k", 5, 60));
        verify(zSetOps).removeRangeByScore(eq("k"), eq(0.0), anyDouble());
        verify(zSetOps).add(eq("k"), anyString(), anyDouble());
        verify(redisTemplate).expire(eq("k"), eq(60L), eq(TimeUnit.SECONDS));
    }

    @Test
    void sliding_atMax_rejects_noAdd() {
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        when(zSetOps.zCard("k")).thenReturn(5L);

        assertFalse(rateLimiter.checkSliding("k", 5, 60));
        verify(zSetOps, never()).add(anyString(), anyString(), anyDouble());
    }

    @Test
    void sliding_nullCard_addsAndPasses() {
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        when(zSetOps.zCard("k")).thenReturn(null);

        assertTrue(rateLimiter.checkSliding("k", 5, 60));
    }

    @Test
    void sliding_redisDown_failsOpen() {
        when(redisTemplate.opsForZSet()).thenThrow(new RuntimeException("redis down"));

        assertTrue(rateLimiter.checkSliding("k", 5, 60));
    }
}
