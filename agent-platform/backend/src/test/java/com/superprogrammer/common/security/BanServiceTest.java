// agent-platform/backend/src/test/java/com/superprogrammer/common/security/BanServiceTest.java
package com.superprogrammer.common.security;

import com.superprogrammer.auth.mapper.UserMapper;
import com.superprogrammer.auth.security.JwtUtil;
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
 * BanService 单测（11x 加固 P1-C3）：revoke 双保险/restore/isBanned/Redis 故障降级。
 */
@ExtendWith(MockitoExtension.class)
class BanServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private JwtUtil jwtUtil;
    @Mock
    private UserMapper userMapper;

    private BanService banService;

    @BeforeEach
    void setUp() {
        banService = new BanService(redisTemplate, jwtUtil, userMapper);
    }

    @Test
    void revoke_deletesSessionAndSetsBanMarker() {
        when(jwtUtil.getAccessExpiration()).thenReturn(900_000L);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        banService.revoke(42L, "BANNED");

        verify(redisTemplate).delete("session:user:42");
        verify(valueOperations).set("ban:user:42", "BANNED", 900_000L, TimeUnit.MILLISECONDS);
    }

    @Test
    void revoke_redisDown_swallowsAndDoesNotThrow() {
        // delete 第一步即抛 → catch 吞掉，后续 opsForValue/getAccessExpiration 不会触达（无需 stub）
        doThrow(new RuntimeException("redis down")).when(redisTemplate).delete("session:user:42");

        assertDoesNotThrow(() -> banService.revoke(42L, "BANNED"));
    }

    @Test
    void restore_deletesBanMarker() {
        banService.restore(42L);

        verify(redisTemplate).delete("ban:user:42");
    }

    @Test
    void restore_redisDown_swallowed() {
        doThrow(new RuntimeException("redis down")).when(redisTemplate).delete("ban:user:42");

        assertDoesNotThrow(() -> banService.restore(42L));
    }

    @Test
    void isBanned_markerExists_true() {
        when(redisTemplate.hasKey("ban:user:42")).thenReturn(true);

        assertTrue(banService.isBanned(42L));
    }

    @Test
    void isBanned_markerMissing_false() {
        when(redisTemplate.hasKey("ban:user:42")).thenReturn(false);

        assertFalse(banService.isBanned(42L));
    }

    @Test
    void isBanned_redisDown_failsOpen() {
        when(redisTemplate.hasKey("ban:user:42")).thenThrow(new RuntimeException("redis down"));

        assertFalse(banService.isBanned(42L));
    }

    // ---- P3-C10 lockAccount ----

    @Test
    void lockAccount_activeUser_updatesDbAndRevokes() {
        when(userMapper.update(isNull(), any())).thenReturn(1);
        when(jwtUtil.getAccessExpiration()).thenReturn(900_000L);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        banService.lockAccount(42L, 15, "LOGIN_BRUTE_FORCE");

        verify(userMapper).update(isNull(), any());
        verify(redisTemplate).delete("session:user:42");
        verify(valueOperations).set("ban:user:42", "LOCKED", 900_000L, TimeUnit.MILLISECONDS);
    }

    @Test
    void lockAccount_notActive_skipsRevoke() {
        // 0 行更新（人工 BANNED/DISABLED 不覆盖）→ 不踢下线
        when(userMapper.update(isNull(), any())).thenReturn(0);

        banService.lockAccount(42L, 15, "LOGIN_BRUTE_FORCE");

        verify(redisTemplate, never()).delete("session:user:42");
    }

    @Test
    void lockAccount_dbDown_swallowed() {
        when(userMapper.update(isNull(), any())).thenThrow(new RuntimeException("db down"));

        assertDoesNotThrow(() -> banService.lockAccount(42L, 15, "X"));
        verify(redisTemplate, never()).delete("session:user:42");
    }
}
