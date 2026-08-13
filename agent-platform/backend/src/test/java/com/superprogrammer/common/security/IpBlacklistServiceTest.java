// agent-platform/backend/src/test/java/com/superprogrammer/common/security/IpBlacklistServiceTest.java
package com.superprogrammer.common.security;

import com.superprogrammer.common.metrics.BizMetrics;
import com.superprogrammer.common.security.mapper.IpBlacklistMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * IpBlacklistService 单测（11x P2-C6）：isBlocked 命中/未中/降级 + autoBlock 双写 + unblock 双删 + 归一化。
 */
@ExtendWith(MockitoExtension.class)
class IpBlacklistServiceTest {

    @Mock
    private IpBlacklistMapper ipBlacklistMapper;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private BizMetrics bizMetrics;

    private IpBlacklistService service;

    @BeforeEach
    void setUp() {
        service = new IpBlacklistService(ipBlacklistMapper, redisTemplate, bizMetrics);
    }

    @Test
    void isBlocked_hit_true() {
        when(redisTemplate.hasKey("ipban:1.2.3.4")).thenReturn(true);
        assertTrue(service.isBlocked("1.2.3.4"));
    }

    @Test
    void isBlocked_miss_false() {
        when(redisTemplate.hasKey("ipban:1.2.3.4")).thenReturn(false);
        assertFalse(service.isBlocked("1.2.3.4"));
    }

    @Test
    void isBlocked_redisDown_failsOpen() {
        when(redisTemplate.hasKey("ipban:1.2.3.4")).thenThrow(new RuntimeException("redis down"));
        assertFalse(service.isBlocked("1.2.3.4"));
    }

    @Test
    void autoBlock_upsertsDbAndMirrorsRedis() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        service.autoBlock("1.2.3.4", SecurityEventTypes.SQLI_PROBE, 60);

        verify(ipBlacklistMapper).upsert(eq("1.2.3.4"), eq("AUTO"), eq(SecurityEventTypes.SQLI_PROBE),
                any(), eq(SecurityEventTypes.SQLI_PROBE));
        verify(valueOperations).set(eq("ipban:1.2.3.4"), eq("1"), longThat(v -> v > 3500 && v <= 3600),
                eq(TimeUnit.SECONDS));
        verify(bizMetrics).ipBlocked("AUTO");
    }

    @Test
    void autoBlock_dbDown_swallowed() {
        when(ipBlacklistMapper.upsert(any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("db down"));
        assertDoesNotThrow(() -> service.autoBlock("1.2.3.4", "X", 60));
    }

    @Test
    void manualBlock_permanent_mirrorsLongTtl() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        service.manualBlock("1.2.3.4", "恶意", true, "admin1");

        verify(ipBlacklistMapper).upsert(eq("1.2.3.4"), eq("MANUAL"), eq("恶意"), isNull(), eq("admin1"));
        // 永久封 Redis 兜底 TTL=30 天
        verify(valueOperations).set(eq("ipban:1.2.3.4"), eq("1"),
                eq(TimeUnit.DAYS.toSeconds(30)), eq(TimeUnit.SECONDS));
    }

    @Test
    void unblock_deletesBoth() {
        service.unblock("1.2.3.4", "admin1");

        verify(ipBlacklistMapper).deleteByIp("1.2.3.4");
        verify(redisTemplate).delete("ipban:1.2.3.4");
    }

    @Test
    void normalize_ipv6Compressed() {
        // 全展开 → 压缩标准形态（防同 IP 多写法绕过）
        String normalized = IpBlacklistService.normalize("0:0:0:0:0:0:0:1");
        assertEquals("0:0:0:0:0:0:0:1", normalized);
    }

    @Test
    void normalize_invalidPassthrough() {
        assertEquals("not-an-ip", IpBlacklistService.normalize("not-an-ip"));
        assertNull(IpBlacklistService.normalize(null));
    }
}
