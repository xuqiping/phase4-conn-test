// agent-platform/backend/src/test/java/com/superprogrammer/auth/service/ProgressiveCaptchaGuardTest.java
package com.superprogrammer.auth.service;

import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** ProgressiveCaptchaGuard 单测（12x B2）：阈值触发/成功清零/Redis 故障降级。 */
@ExtendWith(MockitoExtension.class)
class ProgressiveCaptchaGuardTest {

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOps;
    @Mock
    private CaptchaService captchaService;

    private ProgressiveCaptchaGuard guard;

    @BeforeEach
    void setUp() {
        guard = new ProgressiveCaptchaGuard(redisTemplate, captchaService);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    @Test
    void check_belowThreshold_passesWithoutToken() {
        when(valueOps.get("captcha:need:login:bob")).thenReturn("1");
        assertDoesNotThrow(() -> guard.check("login", "Bob", null));
        verifyNoInteractions(captchaService);
    }

    @Test
    void check_atThresholdWithoutToken_throwsCaptchaInvalid() {
        when(valueOps.get("captcha:need:login:bob")).thenReturn("2");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> guard.check("login", "bob", null));
        assertEquals(ErrorCode.CAPTCHA_INVALID.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("滑块"));
    }

    @Test
    void check_atThresholdWithToken_delegatesVerify() {
        when(valueOps.get("captcha:need:login:bob")).thenReturn("3");
        assertDoesNotThrow(() -> guard.check("login", "bob", "token-abc"));
        verify(captchaService).verify("token-abc");
    }

    @Test
    void recordFailure_firstHit_setsWindowTtl() {
        when(valueOps.increment("captcha:need:register:1.2.3.4")).thenReturn(1L);
        guard.recordFailure("register", "1.2.3.4");
        verify(redisTemplate).expire("captcha:need:register:1.2.3.4", 1800L, TimeUnit.SECONDS);
    }

    @Test
    void recordFailure_subsequentHit_noTtlReset() {
        when(valueOps.increment("captcha:need:register:1.2.3.4")).thenReturn(3L);
        guard.recordFailure("register", "1.2.3.4");
        verify(redisTemplate, never()).expire(anyString(), anyLong(), any());
    }

    @Test
    void clear_deletesKey() {
        guard.clear("forgot", "1.2.3.4");
        verify(redisTemplate).delete("captcha:need:forgot:1.2.3.4");
    }

    @Test
    void redisDown_degradesToAllow() {
        when(valueOps.get(anyString())).thenThrow(new RuntimeException("redis down"));
        assertDoesNotThrow(() -> guard.check("login", "bob", null));
        // 计数/清零也不抛
        when(valueOps.increment(anyString())).thenThrow(new RuntimeException("redis down"));
        assertDoesNotThrow(() -> guard.recordFailure("login", "bob"));
    }

    @Test
    void blankKey_noop() {
        assertDoesNotThrow(() -> guard.check("register", null, null));
        assertDoesNotThrow(() -> guard.recordFailure("register", "  "));
        assertDoesNotThrow(() -> guard.clear("register", null));
        verifyNoInteractions(valueOps);
    }
}
