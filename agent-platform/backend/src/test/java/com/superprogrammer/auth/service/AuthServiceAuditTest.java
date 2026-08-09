// agent-platform/backend/src/test/java/com/superprogrammer/auth/service/AuthServiceAuditTest.java
package com.superprogrammer.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.superprogrammer.auth.dto.LoginRequest;
import com.superprogrammer.auth.dto.RefreshTokenRequest;
import com.superprogrammer.auth.dto.RegisterRequest;
import com.superprogrammer.auth.entity.User;
import com.superprogrammer.auth.mapper.RoleMapper;
import com.superprogrammer.auth.mapper.UserMapper;
import com.superprogrammer.auth.mapper.UserRoleMapper;
import com.superprogrammer.auth.security.JwtUtil;
import com.superprogrammer.common.audit.AuditLogEntity;
import com.superprogrammer.common.audit.AuditLogService;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.system.service.SystemSettingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 日志系统 LOG-FR-11：认证审计埋点测试。
 * 断言：login 成功/三类失败、register、refresh、logout 均产出 module=auth 审计行，
 * 且 detail 只带 reason 码——绝不含密码/token 原文。
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceAuditTest {

    @Mock private UserMapper userMapper;
    @Mock private UserRoleMapper userRoleMapper;
    @Mock private RoleMapper roleMapper;
    @Mock private JwtUtil jwtUtil;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private SystemSettingService systemSettingService;
    @Mock private DepartmentService departmentService;
    @Mock private AuditLogService auditLogService;

    @InjectMocks
    private AuthService authService;

    private User testUser;

    @BeforeEach
    void setUp() {
        testUser = new User();
        testUser.setId(1L);
        testUser.setUsername("testuser");
        testUser.setPassword("$2a$10$encoded");
        testUser.setStatus("ACTIVE");

        // fromMdc 通用桩：按入参建行（lenient——auditFailure 用例会覆盖成抛异常）
        lenient().when(auditLogService.fromMdc(anyString(), anyString(), anyString(),
                        nullable(String.class), nullable(String.class), anyString()))
                .thenAnswer(inv -> {
                    AuditLogEntity row = new AuditLogEntity();
                    row.setModule(inv.getArgument(0));
                    row.setAction(inv.getArgument(1));
                    row.setTargetType(inv.getArgument(2));
                    row.setTargetId(inv.getArgument(3));
                    row.setDetailJson(inv.getArgument(4));
                    row.setResult(inv.getArgument(5));
                    return row;
                });
    }

    @Test
    void login_success_recordsSuccessAudit() {
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(testUser);
        when(passwordEncoder.matches("pw123", testUser.getPassword())).thenReturn(true);
        when(userMapper.selectRoleCodesByUsername("testuser")).thenReturn(List.of("user"));
        when(userMapper.selectPermissionCodesByUserId(1L)).thenReturn(List.of());
        when(systemSettingService.getAccessTokenExpirationMs()).thenReturn(300000L);
        when(jwtUtil.generateAccessToken(eq(1L), eq("testuser"), anyList(), eq(300000L))).thenReturn("at");
        when(jwtUtil.generateRefreshToken(1L)).thenReturn("rt");

        LoginRequest req = new LoginRequest();
        req.setUsername("testuser");
        req.setPassword("pw123");
        authService.login(req);

        ArgumentCaptor<AuditLogEntity> captor = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(auditLogService).record(captor.capture());
        AuditLogEntity row = captor.getValue();
        assertEquals("auth", row.getModule());
        assertEquals("login", row.getAction());
        assertEquals(AuditLogEntity.RESULT_SUCCESS, row.getResult());
        assertEquals(1L, row.getUserId());
        assertEquals("testuser", row.getUsername());
        // 红线：detail 绝不含密码
        assertFalse(String.valueOf(row.getDetailJson()).contains("pw123"));
    }

    @Test
    void login_badPassword_recordsFailAuditWithReasonCode() {
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(testUser);
        when(passwordEncoder.matches("wrong", testUser.getPassword())).thenReturn(false);

        LoginRequest req = new LoginRequest();
        req.setUsername("testuser");
        req.setPassword("wrong");
        assertThrows(BusinessException.class, () -> authService.login(req));

        ArgumentCaptor<AuditLogEntity> captor = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(auditLogService).record(captor.capture());
        AuditLogEntity row = captor.getValue();
        assertEquals(AuditLogEntity.RESULT_FAIL, row.getResult());
        assertTrue(row.getDetailJson().contains("bad_password"));
        assertFalse(row.getDetailJson().contains("wrong")); // 密码原文不落审计
    }

    @Test
    void login_userNotFound_recordsFailAuditWithNullUserId() {
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        LoginRequest req = new LoginRequest();
        req.setUsername("ghost");
        req.setPassword("x");
        assertThrows(BusinessException.class, () -> authService.login(req));

        ArgumentCaptor<AuditLogEntity> captor = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(auditLogService).record(captor.capture());
        AuditLogEntity row = captor.getValue();
        assertNull(row.getUserId());
        assertEquals("ghost", row.getUsername()); // 登录前 MDC 无身份——显式覆盖为尝试的用户名
        assertTrue(row.getDetailJson().contains("user_not_found"));
    }

    @Test
    void register_success_recordsAudit() {
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(passwordEncoder.encode("pw123")).thenReturn("$2a$10$enc");
        when(userMapper.insert(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(7L);
            return 1;
        });
        when(roleMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        RegisterRequest req = new RegisterRequest();
        req.setUsername("newbie");
        req.setPassword("pw123");
        req.setEmail("n@e.com");
        authService.register(req);

        ArgumentCaptor<AuditLogEntity> captor = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(auditLogService).record(captor.capture());
        assertEquals("register", captor.getValue().getAction());
        assertFalse(String.valueOf(captor.getValue().getDetailJson()).contains("pw123"));
    }

    @Test
    void refresh_success_recordsAudit() {
        when(jwtUtil.isTokenValid("rt")).thenReturn(true);
        when(jwtUtil.getTypeFromToken("rt")).thenReturn("refresh");
        when(jwtUtil.getTokenId("rt")).thenReturn("jti-1");
        when(redisTemplate.hasKey(anyString())).thenReturn(false);
        when(jwtUtil.getUserIdFromToken("rt")).thenReturn(1L);
        when(userMapper.selectById(1L)).thenReturn(testUser);
        when(userMapper.selectRoleCodesByUsername("testuser")).thenReturn(List.of("user"));
        when(systemSettingService.getAccessTokenExpirationMs()).thenReturn(300000L);
        when(jwtUtil.generateAccessToken(eq(1L), eq("testuser"), anyList(), eq(300000L))).thenReturn("new-at");

        RefreshTokenRequest req = new RefreshTokenRequest();
        req.setRefreshToken("rt");
        authService.refreshToken(req);

        ArgumentCaptor<AuditLogEntity> captor = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(auditLogService).record(captor.capture());
        assertEquals("refresh", captor.getValue().getAction());
    }

    @Test
    void logout_recordsAuditWithIdentityFromToken() {
        when(jwtUtil.isTokenValid("at")).thenReturn(true);
        when(jwtUtil.getTokenId("at")).thenReturn("jti-a");
        when(jwtUtil.getRemainingTtl("at")).thenReturn(50000L);
        when(jwtUtil.getUserIdFromToken("at")).thenReturn(1L);
        when(jwtUtil.getUsernameFromToken("at")).thenReturn("testuser");
        when(redisTemplate.opsForValue()).thenReturn(mock(org.springframework.data.redis.core.ValueOperations.class));

        authService.logout("at", null);

        ArgumentCaptor<AuditLogEntity> captor = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(auditLogService).record(captor.capture());
        AuditLogEntity row = captor.getValue();
        assertEquals("logout", row.getAction());
        assertEquals(1L, row.getUserId());
        assertEquals("testuser", row.getUsername());
    }

    @Test
    void auditFailure_neverBreaksAuthFlow() {
        // 审计落库抛异常 → 认证主流程照常（审计是 fire-and-forget，异常吞掉只 WARN）
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(testUser);
        when(passwordEncoder.matches("pw123", testUser.getPassword())).thenReturn(true);
        when(userMapper.selectRoleCodesByUsername("testuser")).thenReturn(List.of("user"));
        when(userMapper.selectPermissionCodesByUserId(1L)).thenReturn(List.of());
        when(auditLogService.fromMdc(anyString(), anyString(), anyString(), any(), anyString(), anyString()))
                .thenThrow(new RuntimeException("db down"));

        LoginRequest req = new LoginRequest();
        req.setUsername("testuser");
        req.setPassword("pw123");
        // 审计全炸也不影响签发 token（未 stub 的 jwtUtil 返回 null 即可，关键是不抛审计异常）
        assertDoesNotThrow(() -> authService.login(req));
    }
}
