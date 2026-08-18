package com.superprogrammer.common.ratelimit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * RateLimiter 单测（11x P1-C2）：固定/滑动窗口边界 + Redis 故障降级放行。
 * 固定窗口走原子 Lua（2026-08-19 毒键事故改版）：mock execute 返回计数，脚本自身的
 * 「INCR + TTL&lt;0 自愈挂 EXPIRE」原子性由 Redis 保证，单测只验 Java 侧判定与降级。
 */
@ExtendWith(MockitoExtension.class)
class RateLimiterTest {

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ZSetOperations<String, String> zSetOps;

    private RateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        rateLimiter = new RateLimiter(redisTemplate);
    }

    // ---------- 固定窗口（原子 Lua） ----------

    @SuppressWarnings("unchecked")
    private void stubScript(Long returns) {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), eq("60"))).thenReturn(returns);
    }

    @Test
    void fixed_firstHit_passes() {
        stubScript(1L);

        assertTrue(rateLimiter.checkFixed("k", 5, 60));
        verify(redisTemplate).execute(any(RedisScript.class), eq(List.of("k")), eq("60"));
    }

    @Test
    void fixed_atMax_passes() {
        stubScript(5L);

        assertTrue(rateLimiter.checkFixed("k", 5, 60));
    }

    @Test
    void fixed_overMax_rejects() {
        stubScript(6L);

        assertFalse(rateLimiter.checkFixed("k", 5, 60));
    }

    @Test
    void fixed_poisonedKey_overMax_rejects_untilSelfHealExpiry() {
        // 毒键场景（实证 2026-08-19）：历史无 TTL 键计数 1705 → 超限拒绝；
        // Lua 的 TTL<0 自愈分支会在真实 Redis 重挂 EXPIRE，下一窗口计数归零。
        stubScript(1705L);

        assertFalse(rateLimiter.checkFixed("k", 600, 60));
    }

    @Test
    void fixed_nullResult_passes() {
        stubScript(null);

        assertTrue(rateLimiter.checkFixed("k", 5, 60));
    }

    @Test
    void fixed_redisDown_failsOpen() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyString()))
                .thenThrow(new RuntimeException("redis down"));

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
