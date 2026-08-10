package com.superprogrammer.auth.service;

import com.superprogrammer.auth.security.JwtUtil;
import com.superprogrammer.common.audit.AuditLogEntity;
import com.superprogrammer.common.audit.AuditLogService;
import com.superprogrammer.system.service.SystemSettingService;
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

/**
 * 安全体系 S2 · A8 单点登录（SEC-FR-008）SessionService 单测。
 * 断言：开新会话覆盖写=踢旧（踢的瞬间留痕一次）、sid 比对、开关、Redis 故障降级放行。
 */
@ExtendWith(MockitoExtension.class)
class SessionServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private SystemSettingService systemSettingService;
    @Mock
    private AuditLogService auditLogService;
    @Mock
    private JwtUtil jwtUtil;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private SessionService sessionService;

    @BeforeEach
    void setUp() {
        sessionService = new SessionService(redisTemplate, systemSettingService, auditLogService, jwtUtil);
    }

    private void enabled() {
        lenient().when(systemSettingService.getBoolean(
                SystemSettingService.AUTH_SINGLE_SESSION_ENABLED, true)).thenReturn(true);
    }

    @Test
    void newSession_writesSidWithRefreshTtl() {
        enabled();
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(jwtUtil.getRefreshExpiration()).thenReturn(604800000L);

        String sid = sessionService.newSession(1L, "u1");

        assertNotNull(sid);
        verify(valueOperations).set(eq("session:user:1"), eq(sid), eq(604800000L), eq(TimeUnit.MILLISECONDS));
        verify(auditLogService, never()).record(any()); // 无旧会话 → 不记踢出
    }

    // AC-SEC-FR-008：覆盖写=踢旧，踢的瞬间记一次 session_kicked（防旧 token 每请求刷审计）
    @Test
    void newSession_existingSession_auditsKick() {
        enabled();
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("session:user:1")).thenReturn("old-sid");
        when(jwtUtil.getRefreshExpiration()).thenReturn(604800000L);
        when(auditLogService.fromMdc(eq("auth"), eq("session_kicked"), eq("user"),
                eq("1"), anyString(), eq(AuditLogEntity.RESULT_SUCCESS)))
                .thenReturn(new AuditLogEntity());

        String sid = sessionService.newSession(1L, "u1");

        assertNotEquals("old-sid", sid);
        verify(auditLogService).record(any(AuditLogEntity.class));
    }

    @Test
    void newSession_disabled_skipsRedisWrite() {
        when(systemSettingService.getBoolean(
                SystemSettingService.AUTH_SINGLE_SESSION_ENABLED, true)).thenReturn(false);

        String sid = sessionService.newSession(1L, "u1");

        assertNotNull(sid);
        verify(redisTemplate, never()).opsForValue();
    }

    // 降级红线：Redis 故障 → 仍返回 sid 不抛（比对环节同样故障放行，语义自洽）
    @Test
    void newSession_redisDown_stillReturnsSid() {
        enabled();
        when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("redis down"));

        String sid = assertDoesNotThrow(() -> sessionService.newSession(1L, "u1"));

        assertNotNull(sid);
    }

    @Test
    void isCurrent_disabled_alwaysTrue() {
        when(systemSettingService.getBoolean(
                SystemSettingService.AUTH_SINGLE_SESSION_ENABLED, true)).thenReturn(false);

        assertTrue(sessionService.isCurrent(1L, null));
        verify(redisTemplate, never()).opsForValue();
    }

    // AC-SEC-FR-008：旧无 sid token → false（上线一次性强制重登）
    @Test
    void isCurrent_nullSid_false() {
        enabled();

        assertFalse(sessionService.isCurrent(1L, null));
    }

    @Test
    void isCurrent_matchingSid_true_mismatch_false() {
        enabled();
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("session:user:1")).thenReturn("sid-a");

        assertTrue(sessionService.isCurrent(1L, "sid-a"));
        assertFalse(sessionService.isCurrent(1L, "sid-b"));
    }

    // 降级红线：Redis 故障 → 放行（可用性 > 强制力，与 S1 防爆破一致）
    @Test
    void isCurrent_redisDown_degradesOpen() {
        enabled();
        when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("redis down"));

        assertTrue(sessionService.isCurrent(1L, "sid-a"));
    }

    // 降级红线：开关读取失败（DB 抖动）→ 放行（不杀主链，与 Redis 故障同范式）
    @Test
    void isCurrent_settingsReadFails_degradesOpen() {
        when(systemSettingService.getBoolean(SystemSettingService.AUTH_SINGLE_SESSION_ENABLED, true))
                .thenThrow(new RuntimeException("db down"));

        assertTrue(sessionService.isCurrent(1L, "sid-a"));
    }

    @Test
    void clearSession_sidMatches_deletesKey() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("session:user:1")).thenReturn("sid-a");

        sessionService.clearSession(1L, "sid-a");

        verify(redisTemplate).delete("session:user:1");
    }

    // F2 防 logout-bomb：旧（被踢）会话登出不得删掉新会话的键
    @Test
    void clearSession_sidMismatch_keepsCurrentSession() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("session:user:1")).thenReturn("sid-new");

        sessionService.clearSession(1L, "sid-old");

        verify(redisTemplate, never()).delete(anyString());
    }

    @Test
    void clearSession_nullSafe() {
        sessionService.clearSession(null, "sid-a");
        sessionService.clearSession(1L, null);

        verifyNoInteractions(redisTemplate);
    }
}
