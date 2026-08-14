// agent-platform/backend/src/test/java/com/superprogrammer/auth/service/AuthServiceTest.java
package com.superprogrammer.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.superprogrammer.auth.dto.*;
import com.superprogrammer.auth.entity.User;
import com.superprogrammer.auth.entity.UserRole;
import com.superprogrammer.auth.mapper.RoleMapper;
import com.superprogrammer.auth.mapper.UserMapper;
import com.superprogrammer.auth.mapper.UserRoleMapper;
import com.superprogrammer.auth.security.JwtUtil;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.audit.AuditLogService;
import com.superprogrammer.system.service.SystemSettingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserMapper userMapper;

    @Mock
    private UserRoleMapper userRoleMapper;

    @Mock
    private RoleMapper roleMapper;

    @Mock
    private JwtUtil jwtUtil;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private SystemSettingService systemSettingService;

    @Mock
    private DepartmentService departmentService;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private com.superprogrammer.common.metrics.BizMetrics bizMetrics;

    @Mock
    private SessionService sessionService;

    @Mock
    private CredentialService credentialService;

    @Mock
    private LoginAlertService loginAlertService;

    // 11x 加固 P2-C7 新增依赖（不 stub=默认 no-op，不影响既有断言）
    @Mock
    private com.superprogrammer.common.security.LoginAttemptsService loginAttemptsService;

    @Mock
    private com.superprogrammer.common.security.IpBlacklistService ipBlacklistService;

    @Mock
    private com.superprogrammer.common.security.SecurityEventService securityEventService;

    @Mock
    private com.superprogrammer.common.security.BanService banService;

    @Mock
    private com.superprogrammer.auth.service.MfaService mfaService;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private AuthService authService;

    private User testUser;
    private RegisterRequest registerRequest;
    private LoginRequest loginRequest;

    @BeforeEach
    void setUp() {
        testUser = new User();
        testUser.setId(1L);
        testUser.setUsername("testuser");
        testUser.setPassword("$2a$10$encoded_password");
        testUser.setEmail("test@example.com");
        testUser.setStatus("ACTIVE");
        testUser.setCreatedAt(OffsetDateTime.now());
        testUser.setUpdatedAt(OffsetDateTime.now());

        registerRequest = new RegisterRequest();
        registerRequest.setUsername("newuser");
        registerRequest.setPassword("Str0ng#Pass");
        registerRequest.setEmail("new@example.com");

        loginRequest = new LoginRequest();
        loginRequest.setUsername("testuser");
        loginRequest.setPassword("password123");
    }

    @Test
    void register_success() {
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(passwordEncoder.encode("Str0ng#Pass")).thenReturn("$2a$10$encoded");
        when(userMapper.insert(any(User.class))).thenReturn(1);
        when(roleMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        assertDoesNotThrow(() -> authService.register(registerRequest));

        verify(userMapper).insert(argThat(user ->
                user.getUsername().equals("newuser") &&
                user.getEmail().equals("new@example.com")
        ));
    }

    @Test
    void register_duplicateUsername_throwsException() {
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(testUser);

        assertThrows(BusinessException.class, () -> authService.register(registerRequest));
    }

    // 密码策略：弱密码在查重之后、写库之前被拒（不落库不审计成功）
    @Test
    void register_weakPassword_rejected() {
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        registerRequest.setPassword("123456");

        BusinessException e = assertThrows(BusinessException.class, () -> authService.register(registerRequest));

        assertEquals(400, e.getCode());
        verify(userMapper, never()).insert(any(User.class));
    }

    @Test
    void login_success() {
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(testUser);
        when(passwordEncoder.matches("password123", testUser.getPassword())).thenReturn(true);
        when(systemSettingService.getAccessTokenExpirationMs()).thenReturn(300000L);
        when(jwtUtil.generateAccessToken(eq(1L), eq("testuser"), anyList(), eq(300000L), any())).thenReturn("access-token");
        when(jwtUtil.generateRefreshToken(eq(1L), any())).thenReturn("refresh-token");
        when(userMapper.selectRoleCodesByUsername("testuser")).thenReturn(Arrays.asList("user"));
        when(userMapper.selectPermissionCodesByUserId(1L)).thenReturn(Arrays.asList("agent:read"));
        when(userMapper.updateById(any(User.class))).thenReturn(1);

        TokenResponse response = authService.login(loginRequest);

        assertNotNull(response);
        assertEquals("access-token", response.getAccessToken());
        assertEquals("refresh-token", response.getRefreshToken());
        assertEquals("Bearer", response.getTokenType());
        assertEquals(300000L, response.getExpiresIn());
        assertNotNull(response.getUserInfo());
        assertEquals(1L, response.getUserInfo().getId());
        assertEquals("testuser", response.getUserInfo().getUsername());
    }

    @Test
    void login_wrongPassword_throwsException() {
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(testUser);
        when(passwordEncoder.matches("password123", testUser.getPassword())).thenReturn(false);

        assertThrows(BusinessException.class, () -> authService.login(loginRequest));
    }

    // ===== OPS-FR-07 登录/限流指标 =====

    @Test
    void login_success_countsAuthLoginSuccess() {
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(testUser);
        when(passwordEncoder.matches("password123", testUser.getPassword())).thenReturn(true);
        when(systemSettingService.getAccessTokenExpirationMs()).thenReturn(300000L);
        when(jwtUtil.generateAccessToken(eq(1L), eq("testuser"), anyList(), eq(300000L), any())).thenReturn("access-token");
        when(jwtUtil.generateRefreshToken(eq(1L), any())).thenReturn("refresh-token");
        when(userMapper.selectRoleCodesByUsername("testuser")).thenReturn(Arrays.asList("user"));
        when(userMapper.selectPermissionCodesByUserId(1L)).thenReturn(Arrays.asList("agent:read"));
        when(userMapper.updateById(any(User.class))).thenReturn(1);

        authService.login(loginRequest);

        verify(bizMetrics).authLogin("success");
        verify(bizMetrics, never()).authLogin("fail");
    }

    @Test
    void login_wrongPassword_countsAuthLoginFail() {
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(testUser);
        when(passwordEncoder.matches("password123", testUser.getPassword())).thenReturn(false);

        assertThrows(BusinessException.class, () -> authService.login(loginRequest));

        verify(bizMetrics).authLogin("fail");
        verify(bizMetrics, never()).authLogin("success");
    }

    @Test
    void register_rateLimited_countsOnceAndRethrows() {
        // 无请求上下文 → IP 窗口跳过；用户名窗口 increment=6 超阈值(5) → RATE_LIMIT
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment("ratelimit:register:user:newuser")).thenReturn(6L);

        BusinessException e = assertThrows(BusinessException.class, () -> authService.register(registerRequest));

        assertEquals(429, e.getCode());
        verify(bizMetrics, times(1)).registerRateLimited();
    }

    @Test
    void login_userNotFound_throwsException() {
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        assertThrows(BusinessException.class, () -> authService.login(loginRequest));
    }

    // ===== 安全体系 S1 · SEC-FR-001 登录防爆破 =====

    // AC-SEC-FR-001：账号失败计数 ≥5 → 前置闸拒绝，固定话术，连库都不查
    @Test
    void login_accountLocked_rejectedBeforeDb() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("login:fail:u:testuser")).thenReturn("5");

        BusinessException e = assertThrows(BusinessException.class, () -> authService.login(loginRequest));

        assertEquals(40103, e.getCode());   // LOGIN_LOCKED
        verify(bizMetrics).authLoginLocked("account");
        verify(userMapper, never()).selectOne(any(LambdaQueryWrapper.class));
    }

    // AC-SEC-FR-001：第 5 次失败跃迁 → 写 login_locked 安全审计（仅跃迁一次）
    @Test
    void login_fifthFailure_auditsLoginLocked() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment("login:fail:u:testuser")).thenReturn(5L);
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(testUser);
        when(passwordEncoder.matches("password123", testUser.getPassword())).thenReturn(false);

        assertThrows(BusinessException.class, () -> authService.login(loginRequest));

        verify(bizMetrics).authLoginLocked("account");
        verify(auditLogService).fromMdc(eq("auth"), eq("login_locked"), eq("user"),
                eq("1"), contains("fail_count_5"), eq("FAIL"));
    }

    // AC-SEC-FR-001：同 IP 1h 失败 >20 → 封禁（带请求上下文供 IP 解析）
    @Test
    void login_ipBanned_rejected() {
        org.springframework.mock.web.MockHttpServletRequest req = new org.springframework.mock.web.MockHttpServletRequest();
        req.setRemoteAddr("9.9.9.9");
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                new org.springframework.web.context.request.ServletRequestAttributes(req));
        try {
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.get("login:fail:u:testuser")).thenReturn(null);
            when(valueOperations.get("login:fail:ip:9.9.9.9")).thenReturn("21");

            BusinessException e = assertThrows(BusinessException.class, () -> authService.login(loginRequest));

            assertEquals(40103, e.getCode());
            verify(bizMetrics).authLoginLocked("ip");
        } finally {
            org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
        }
    }

    // AC-SEC-FR-001：Redis 故障降级放行——登录主链不被打死（user_not_found 仍正常走）
    @Test
    void login_redisDown_degradesOpen() {
        when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("redis down"));
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        BusinessException e = assertThrows(BusinessException.class, () -> authService.login(loginRequest));

        assertEquals(401, e.getCode());   // 走的是正常「用户名或密码错误」，不是 500
    }

    // AC-SEC-FR-001：登录成功清账号失败计数
    @Test
    void login_success_clearsAccountFailCounter() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(testUser);
        when(passwordEncoder.matches("password123", testUser.getPassword())).thenReturn(true);
        when(systemSettingService.getAccessTokenExpirationMs()).thenReturn(300000L);
        when(jwtUtil.generateAccessToken(eq(1L), eq("testuser"), anyList(), eq(300000L), any())).thenReturn("access-token");
        when(jwtUtil.generateRefreshToken(eq(1L), any())).thenReturn("refresh-token");
        when(userMapper.selectRoleCodesByUsername("testuser")).thenReturn(Arrays.asList("user"));
        when(userMapper.selectPermissionCodesByUserId(1L)).thenReturn(Arrays.asList("agent:read"));
        when(userMapper.updateById(any(User.class))).thenReturn(1);

        authService.login(loginRequest);

        verify(redisTemplate).delete("login:fail:u:testuser");
    }

    @Test
    void refreshToken_success() {
        RefreshTokenRequest request = new RefreshTokenRequest();
        request.setRefreshToken("valid-refresh-token");

        when(jwtUtil.isTokenValid("valid-refresh-token")).thenReturn(true);
        when(jwtUtil.getTypeFromToken("valid-refresh-token")).thenReturn("refresh");
        when(jwtUtil.getUserIdFromToken("valid-refresh-token")).thenReturn(1L);
        when(jwtUtil.getSidFromToken("valid-refresh-token")).thenReturn("sid-1");
        when(sessionService.isCurrent(1L, "sid-1")).thenReturn(true);
        when(redisTemplate.hasKey(anyString())).thenReturn(false);
        when(systemSettingService.getAccessTokenExpirationMs()).thenReturn(300000L);
        when(jwtUtil.generateAccessToken(eq(1L), anyString(), anyList(), eq(300000L), eq("sid-1"))).thenReturn("new-access-token");
        when(userMapper.selectById(1L)).thenReturn(testUser);
        when(userMapper.selectRoleCodesByUsername("testuser")).thenReturn(Arrays.asList("user"));

        TokenResponse response = authService.refreshToken(request);

        assertNotNull(response);
        assertEquals("new-access-token", response.getAccessToken());
        assertEquals(300000L, response.getExpiresIn());
    }

    @Test
    void refreshToken_invalidToken_throwsException() {
        RefreshTokenRequest request = new RefreshTokenRequest();
        request.setRefreshToken("invalid-token");

        when(jwtUtil.isTokenValid("invalid-token")).thenReturn(false);

        assertThrows(BusinessException.class, () -> authService.refreshToken(request));
    }

    // ---- 安全体系 S5 · SEC-FR-004+（A4 refresh 旋转）----    @Test
    void refreshToken_rotationEnabled_rotatesAndBlacklistsOld() {
        RefreshTokenRequest request = new RefreshTokenRequest();
        request.setRefreshToken("old-refresh");

        when(jwtUtil.isTokenValid("old-refresh")).thenReturn(true);
        when(jwtUtil.getTypeFromToken("old-refresh")).thenReturn("refresh");
        when(jwtUtil.getTokenId("old-refresh")).thenReturn("jti-1");
        when(redisTemplate.hasKey(anyString())).thenReturn(false);
        when(jwtUtil.getUserIdFromToken("old-refresh")).thenReturn(1L);
        when(jwtUtil.getSidFromToken("old-refresh")).thenReturn("sid-1");
        when(sessionService.isCurrent(1L, "sid-1")).thenReturn(true);
        when(userMapper.selectById(1L)).thenReturn(testUser);
        when(userMapper.selectRoleCodesByUsername("testuser")).thenReturn(Arrays.asList("user"));
        when(systemSettingService.getAccessTokenExpirationMs()).thenReturn(300000L);
        when(jwtUtil.generateAccessToken(eq(1L), anyString(), anyList(), eq(300000L), eq("sid-1"))).thenReturn("new-access");
        when(systemSettingService.getAuthRefreshRotationEnabled()).thenReturn(true);
        when(jwtUtil.getRemainingTtl("old-refresh")).thenReturn(600000L);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(jwtUtil.generateRefreshToken(1L, "sid-1")).thenReturn("new-refresh");

        TokenResponse response = authService.refreshToken(request);

        assertEquals("new-access", response.getAccessToken());
        assertEquals("new-refresh", response.getRefreshToken());
        // 旧票拉黑：值=rotated（区别于 logout 的 "1"），TTL=剩余有效期
        verify(valueOperations).set(eq("token:blacklist:jti-1"), eq("rotated"), eq(600000L), eq(TimeUnit.MILLISECONDS));
        verify(bizMetrics).authRefreshRotated();
    }

    @Test
    void refreshToken_rotationDisabled_reusesOldRefresh() {
        RefreshTokenRequest request = new RefreshTokenRequest();
        request.setRefreshToken("old-refresh");

        when(jwtUtil.isTokenValid("old-refresh")).thenReturn(true);
        when(jwtUtil.getTypeFromToken("old-refresh")).thenReturn("refresh");
        when(jwtUtil.getTokenId("old-refresh")).thenReturn("jti-1");
        when(redisTemplate.hasKey(anyString())).thenReturn(false);
        when(jwtUtil.getUserIdFromToken("old-refresh")).thenReturn(1L);
        when(jwtUtil.getSidFromToken("old-refresh")).thenReturn("sid-1");
        when(sessionService.isCurrent(1L, "sid-1")).thenReturn(true);
        when(userMapper.selectById(1L)).thenReturn(testUser);
        when(userMapper.selectRoleCodesByUsername("testuser")).thenReturn(Arrays.asList("user"));
        when(systemSettingService.getAccessTokenExpirationMs()).thenReturn(300000L);
        when(jwtUtil.generateAccessToken(eq(1L), anyString(), anyList(), eq(300000L), eq("sid-1"))).thenReturn("new-access");
        when(systemSettingService.getAuthRefreshRotationEnabled()).thenReturn(false);

        TokenResponse response = authService.refreshToken(request);

        assertEquals("old-refresh", response.getRefreshToken());   // 回传旧票（旧行为）
        verify(redisTemplate, never()).opsForValue();
        verify(jwtUtil, never()).generateRefreshToken(anyLong(), anyString());
        verify(bizMetrics, never()).authRefreshRotated();
    }

    @Test
    void refreshToken_replayedRotatedToken_rejectedAndAlerted() {
        RefreshTokenRequest request = new RefreshTokenRequest();
        request.setRefreshToken("stolen-refresh");

        when(jwtUtil.isTokenValid("stolen-refresh")).thenReturn(true);
        when(jwtUtil.getTypeFromToken("stolen-refresh")).thenReturn("refresh");
        when(jwtUtil.getTokenId("stolen-refresh")).thenReturn("jti-stolen");
        when(redisTemplate.hasKey("token:blacklist:jti-stolen")).thenReturn(true);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("token:blacklist:jti-stolen")).thenReturn("rotated");   // 旋转拉黑标记=重放

        BusinessException e = assertThrows(BusinessException.class, () -> authService.refreshToken(request));

        assertEquals(40102, e.getCode());   // TOKEN_INVALID 固定话术防探测
        verify(bizMetrics).authRefreshReplayed();
        verify(userMapper, never()).selectById(anyLong());   // 不进签发链
    }

    @Test
    void refreshToken_loggedOutBlacklistedToken_plainInvalidNoReplayAlert() {
        RefreshTokenRequest request = new RefreshTokenRequest();
        request.setRefreshToken("logged-out-refresh");

        when(jwtUtil.isTokenValid("logged-out-refresh")).thenReturn(true);
        when(jwtUtil.getTypeFromToken("logged-out-refresh")).thenReturn("refresh");
        when(jwtUtil.getTokenId("logged-out-refresh")).thenReturn("jti-logout");
        when(redisTemplate.hasKey("token:blacklist:jti-logout")).thenReturn(true);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("token:blacklist:jti-logout")).thenReturn("1");   // logout 拉黑≠重放

        BusinessException e = assertThrows(BusinessException.class, () -> authService.refreshToken(request));

        assertEquals(40102, e.getCode());
        verify(bizMetrics, never()).authRefreshReplayed();   // 正常登出过期不告警
    }

    // ---- 安全体系 S5 · SEC-FR-006（A6 TOTP 两步登录）----

    // 已绑定用户：密码步通过 → 只回 mfaRequired+mfaToken，不发双 token
    @Test
    void login_mfaBound_returnsMfaTokenOnly() {
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(testUser);
        when(passwordEncoder.matches("password123", testUser.getPassword())).thenReturn(true);
        when(userMapper.selectRoleCodesByUsername("testuser")).thenReturn(Arrays.asList("user"));
        when(userMapper.selectPermissionCodesByUserId(1L)).thenReturn(Arrays.asList("agent:read"));
        when(mfaService.isBound(1L)).thenReturn(true);
        when(jwtUtil.generateMfaToken(1L)).thenReturn("mfa-token");

        TokenResponse response = authService.login(loginRequest);

        assertEquals(Boolean.TRUE, response.getMfaRequired());
        assertEquals("mfa-token", response.getMfaToken());
        assertNull(response.getAccessToken());   // 双 token 未签发
        assertNull(response.getRefreshToken());
        verify(jwtUtil, never()).generateAccessToken(anyLong(), anyString(), anyList(), anyLong(), any());
    }

    // 未绑定 + totp.required 开 + admin → 正常发 token 且带绑定建议标记（不阻断）
    @Test
    void login_totpRequiredAdminUnbound_setsBindAdvice() {
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(testUser);
        when(passwordEncoder.matches("password123", testUser.getPassword())).thenReturn(true);
        when(userMapper.selectRoleCodesByUsername("testuser")).thenReturn(Arrays.asList("admin"));
        when(userMapper.selectPermissionCodesByUserId(1L)).thenReturn(Arrays.asList("agent:read"));
        when(systemSettingService.getAccessTokenExpirationMs()).thenReturn(300000L);
        when(jwtUtil.generateAccessToken(eq(1L), eq("testuser"), anyList(), eq(300000L), any())).thenReturn("access-token");
        when(jwtUtil.generateRefreshToken(eq(1L), any())).thenReturn("refresh-token");
        when(userMapper.updateById(any(User.class))).thenReturn(1);
        when(systemSettingService.getAuthTotpRequired()).thenReturn(true);
        when(mfaService.isBound(1L)).thenReturn(false);

        TokenResponse response = authService.login(loginRequest);

        assertEquals("access-token", response.getAccessToken());
        assertEquals(Boolean.TRUE, response.getMfaBindAdvice());
    }

    // 第二屏校验通过：消费 mfaToken（jti 拉黑）+ 发双 token
    @Test
    void verifyMfa_validCode_issuesTokensAndConsumesMfaToken() {
        MfaVerifyRequest request = new MfaVerifyRequest();
        request.setMfaToken("mfa-token");
        request.setCode("123456");

        when(jwtUtil.isTokenValid("mfa-token")).thenReturn(true);
        when(jwtUtil.getTypeFromToken("mfa-token")).thenReturn("mfa");
        when(jwtUtil.getTokenId("mfa-token")).thenReturn("mfa-jti");
        when(jwtUtil.getUserIdFromToken("mfa-token")).thenReturn(1L);
        when(redisTemplate.hasKey("token:blacklist:mfa-jti")).thenReturn(false);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment("mfa:tries:mfa-jti")).thenReturn(1L);
        when(mfaService.verifyAndConsume(1L, "123456", true)).thenReturn(true);
        when(userMapper.selectById(1L)).thenReturn(testUser);
        when(userMapper.selectRoleCodesByUsername("testuser")).thenReturn(Arrays.asList("admin"));
        when(userMapper.selectPermissionCodesByUserId(1L)).thenReturn(Arrays.asList("agent:read"));
        when(systemSettingService.getAccessTokenExpirationMs()).thenReturn(300000L);
        when(jwtUtil.generateAccessToken(eq(1L), eq("testuser"), anyList(), eq(300000L), any())).thenReturn("access-token");
        when(jwtUtil.generateRefreshToken(eq(1L), any())).thenReturn("refresh-token");
        when(userMapper.updateById(any(User.class))).thenReturn(1);

        TokenResponse response = authService.verifyMfa(request);

        assertEquals("access-token", response.getAccessToken());
        assertNull(response.getMfaRequired());   // 完整登录响应
        // 一次性：mfaToken jti 拉黑 5min（= 票自然寿命）
        verify(valueOperations).set(eq("token:blacklist:mfa-jti"), eq("1"), eq(300L), eq(TimeUnit.SECONDS));
        verify(bizMetrics).authMfaVerify("success");
    }

    // 验证码错误：401 + 不进用户查询/不发 token + mfaToken 不作废（可重试，5 次封顶）
    @Test
    void verifyMfa_wrongCode_rejectedRetryable() {
        MfaVerifyRequest request = new MfaVerifyRequest();
        request.setMfaToken("mfa-token");
        request.setCode("000000");

        when(jwtUtil.isTokenValid("mfa-token")).thenReturn(true);
        when(jwtUtil.getTypeFromToken("mfa-token")).thenReturn("mfa");
        when(jwtUtil.getTokenId("mfa-token")).thenReturn("mfa-jti");
        when(jwtUtil.getUserIdFromToken("mfa-token")).thenReturn(1L);
        when(redisTemplate.hasKey("token:blacklist:mfa-jti")).thenReturn(false);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment("mfa:tries:mfa-jti")).thenReturn(1L);
        when(mfaService.verifyAndConsume(1L, "000000", true)).thenReturn(false);

        BusinessException e = assertThrows(BusinessException.class, () -> authService.verifyMfa(request));

        assertEquals(401, e.getCode());
        verify(valueOperations, never()).set(eq("token:blacklist:mfa-jti"), anyString(), anyLong(), any());
        verify(userMapper, never()).selectById(anyLong());
        verify(bizMetrics).authMfaVerify("fail");
    }

    // 已消费过的 mfaToken（重放）：直接 40102，不进验证码比对
    @Test
    void verifyMfa_consumedMfaToken_rejected() {
        MfaVerifyRequest request = new MfaVerifyRequest();
        request.setMfaToken("mfa-token");
        request.setCode("123456");

        when(jwtUtil.isTokenValid("mfa-token")).thenReturn(true);
        when(jwtUtil.getTypeFromToken("mfa-token")).thenReturn("mfa");
        when(jwtUtil.getTokenId("mfa-token")).thenReturn("mfa-jti");
        when(redisTemplate.hasKey("token:blacklist:mfa-jti")).thenReturn(true);

        BusinessException e = assertThrows(BusinessException.class, () -> authService.verifyMfa(request));

        assertEquals(40102, e.getCode());
        verify(mfaService, never()).verifyAndConsume(anyLong(), anyString(), anyBoolean());
    }

    // AC-SEC-FR-008：被踢会话的 refresh 同样拒绝（40104 固定话术），不查库不签发
    @Test
    void refreshToken_kickedSession_rejected() {
        RefreshTokenRequest request = new RefreshTokenRequest();
        request.setRefreshToken("old-refresh-token");

        when(jwtUtil.isTokenValid("old-refresh-token")).thenReturn(true);
        when(jwtUtil.getTypeFromToken("old-refresh-token")).thenReturn("refresh");
        when(jwtUtil.getTokenId("old-refresh-token")).thenReturn("jti-old");
        when(redisTemplate.hasKey(anyString())).thenReturn(false);
        when(jwtUtil.getUserIdFromToken("old-refresh-token")).thenReturn(1L);
        when(jwtUtil.getSidFromToken("old-refresh-token")).thenReturn("sid-old");
        when(sessionService.isCurrent(1L, "sid-old")).thenReturn(false);

        BusinessException e = assertThrows(BusinessException.class, () -> authService.refreshToken(request));

        assertEquals(40104, e.getCode());   // SESSION_KICKED
        verify(userMapper, never()).selectById(anyLong());
    }

    @Test
    void logout_success() {
        String accessToken = "valid-access-token";
        String refreshToken = "valid-refresh-token";

        when(jwtUtil.isTokenValid(accessToken)).thenReturn(true);
        when(jwtUtil.isTokenValid(refreshToken)).thenReturn(true);
        when(jwtUtil.getTokenId(accessToken)).thenReturn("access-jti");
        when(jwtUtil.getTokenId(refreshToken)).thenReturn("refresh-jti");
        when(jwtUtil.getRemainingTtl(accessToken)).thenReturn(50000L);
        when(jwtUtil.getRemainingTtl(refreshToken)).thenReturn(3000000L);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        assertDoesNotThrow(() -> authService.logout(accessToken, refreshToken));

        verify(valueOperations, times(2)).set(anyString(), eq("1"), anyLong(), eq(TimeUnit.MILLISECONDS));
    }

    // 降级红线：Redis 故障 → 黑名单查询放行（可用性 > 强制力，不杀认证主链）
    @Test
    void isTokenBlacklisted_redisDown_degradesOpen() {
        when(redisTemplate.hasKey(anyString())).thenThrow(new RuntimeException("redis down"));

        assertFalse(authService.isTokenBlacklisted("any-jti"));
    }

    // 降级红线：logout 黑名单写失败不阻断登出（token 残留至自然过期，access 仅 15min）
    @Test
    void logout_redisDown_stillSucceeds() {
        String accessToken = "valid-access-token";
        when(jwtUtil.isTokenValid(accessToken)).thenReturn(true);
        when(jwtUtil.getTokenId(accessToken)).thenReturn("access-jti");
        when(jwtUtil.getRemainingTtl(accessToken)).thenReturn(50000L);
        when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("redis down"));

        assertDoesNotThrow(() -> authService.logout(accessToken, null));
    }
}
