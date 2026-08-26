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
import com.superprogrammer.common.exception.ErrorCode;
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
import java.util.List;
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

    // 12x B1：注册前置邮箱验证码校验
    @Mock
    private com.superprogrammer.auth.service.EmailService emailService;

    // 12x B2：渐进式滑块门槛
    @Mock
    private com.superprogrammer.auth.service.ProgressiveCaptchaGuard captchaGuard;

    // 12x 开关回退：邮箱验证总开关
    @Mock
    private com.superprogrammer.auth.service.AuthChannelSettingService channelSettings;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private AuthService authService;

    private User testUser;
    private RegisterRequest registerRequest;
    private LoginRequest loginRequest;

    @org.junit.jupiter.api.BeforeAll
    static void initTableInfo() {
        // 修复III E1：LambdaUpdateWrapper.set/LambdaQueryWrapper.eq 需 MP lambda 缓存
        // （沉淀规范：纯 Mockito 测须 initTableInfo；此前 updateProfile 用例靠全量跑时
        // 其他测试先注册 User 表信息，单跑本类即红）
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                User.class);
    }

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
        registerRequest.setName("新人");
        registerRequest.setPassword("Str0ng#Pass");
        registerRequest.setEmail("new@example.com");
        registerRequest.setEmailCode("123456");

        // 12x 开关回退：默认开（保持 B1 既有语义）；「关」场景由专项用例覆盖
        org.mockito.Mockito.lenient().when(channelSettings.isEmailVerificationRequired()).thenReturn(true);

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

    // 12x B1：验证码不过 → 直接拒（不建用户）；且 EMAIL 凭证带 verified=TRUE 建
    @Test
    void register_emailCodeInvalid_throwsAndSkipsInsert() {
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        doThrow(new BusinessException(ErrorCode.BAD_REQUEST, "验证码错误"))
                .when(emailService).verifyRegisterCode("new@example.com", "123456");

        BusinessException e = assertThrows(BusinessException.class, () -> authService.register(registerRequest));

        assertEquals(400, e.getCode());
        verify(userMapper, never()).insert(any(User.class));
    }

    @Test
    void register_emailCredentialCreatedVerified() {
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(passwordEncoder.encode("Str0ng#Pass")).thenReturn("$2a$10$encoded");
        when(userMapper.insert(any(User.class))).thenReturn(1);
        when(roleMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        authService.register(registerRequest);

        verify(emailService).verifyRegisterCode("new@example.com", "123456");
        verify(credentialService).createCredential(any(), eq(com.superprogrammer.auth.entity.UserCredential.TYPE_EMAIL),
                eq("new@example.com"), isNull(), eq(true));
    }

    // 12x 开关回退：总开关关 → 不验码直接注册；邮箱填了建 EMAIL 凭证但 verified=FALSE（留待后验）
    @Test
    void register_switchOff_skipsCode_emailCredentialUnverified() {
        when(channelSettings.isEmailVerificationRequired()).thenReturn(false);
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(passwordEncoder.encode("Str0ng#Pass")).thenReturn("$2a$10$encoded");
        when(userMapper.insert(any(User.class))).thenReturn(1);
        when(roleMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        registerRequest.setEmailCode(null);

        authService.register(registerRequest);

        verify(emailService, never()).verifyRegisterCode(any(), any());
        verify(credentialService).createCredential(any(), eq(com.superprogrammer.auth.entity.UserCredential.TYPE_EMAIL),
                eq("new@example.com"), isNull(), eq(false));
    }

    // 12x 开关回退：总开关关 → 邮箱可留空注册（不建 EMAIL 凭证）
    @Test
    void register_switchOff_emailBlank_ok() {
        when(channelSettings.isEmailVerificationRequired()).thenReturn(false);
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(passwordEncoder.encode("Str0ng#Pass")).thenReturn("$2a$10$encoded");
        when(userMapper.insert(any(User.class))).thenReturn(1);
        when(roleMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        registerRequest.setEmail(null);
        registerRequest.setEmailCode(null);

        authService.register(registerRequest);

        verify(userMapper).insert(argThat(user -> user.getEmail() == null));
        verify(credentialService, never()).createCredential(any(),
                eq(com.superprogrammer.auth.entity.UserCredential.TYPE_EMAIL), any(), any(), any(Boolean.class));
    }

    // 12x 开关回退：总开关开 → 邮箱/验证码缺失直接 400（DTO 不再静态 @NotBlank，服务端判）
    @Test
    void register_switchOn_emailOrCodeBlank_rejected() {
        registerRequest.setEmail(" ");
        BusinessException e1 = assertThrows(BusinessException.class, () -> authService.register(registerRequest));
        assertEquals(400, e1.getCode());

        registerRequest.setEmail("new@example.com");
        registerRequest.setEmailCode(null);
        BusinessException e2 = assertThrows(BusinessException.class, () -> authService.register(registerRequest));
        assertEquals(400, e2.getCode());
        verify(userMapper, never()).insert(any(User.class));
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

    // ===== D1（12x-1）：注册备注 =====

    @Test
    void register_remarkTrimmed_persisted() {
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(passwordEncoder.encode("Str0ng#Pass")).thenReturn("$2a$10$encoded");
        when(userMapper.insert(any(User.class))).thenReturn(1);
        when(roleMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        registerRequest.setRemark("  A 班  ");

        authService.register(registerRequest);

        verify(userMapper).insert(argThat(user -> "A 班".equals(user.getRemark())));
    }

    @Test
    void register_remarkBlank_null() {
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(passwordEncoder.encode("Str0ng#Pass")).thenReturn("$2a$10$encoded");
        when(userMapper.insert(any(User.class))).thenReturn(1);
        when(roleMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        registerRequest.setRemark("   ");

        authService.register(registerRequest);

        verify(userMapper).insert(argThat(user -> user.getRemark() == null));
    }

    // ===== D1：本人改备注（与昵称同链路 updateProfile） =====

    @Test
    void updateProfile_remarkAndName_trimmedAndCleared() {
        testUser.setRemark("旧备注");
        // 第一次 selectById=updateProfile 读旧值；第二次=getCurrentUser 回读「已更新」行
        User updated = new User();
        updated.setId(1L);
        updated.setUsername("testuser");
        updated.setName("新名字");
        updated.setRemark("B 班");
        updated.setStatus("ACTIVE");
        when(userMapper.selectById(1L)).thenReturn(testUser, updated);
        when(userMapper.selectRoleCodesByUsername("testuser")).thenReturn(List.of("user"));
        when(userMapper.selectPermissionCodesByUserId(1L)).thenReturn(List.of());
        when(departmentService.getPrimaryDepartmentName(1L)).thenReturn(null);

        com.superprogrammer.auth.dto.UserVO vo = authService.updateProfile(1L, " 新名字 ", "  B 班  ");

        assertEquals("B 班", vo.getRemark());
        assertEquals("新名字", vo.getName());
    }

    @Test
    void updateProfile_remarkBlank_cleared() {
        testUser.setName("老名字");
        testUser.setRemark("旧备注");
        User updated = new User();
        updated.setId(1L);
        updated.setUsername("testuser");
        updated.setName("老名字");
        updated.setStatus("ACTIVE");
        when(userMapper.selectById(1L)).thenReturn(testUser, updated);
        when(userMapper.selectRoleCodesByUsername("testuser")).thenReturn(List.of("user"));
        when(userMapper.selectPermissionCodesByUserId(1L)).thenReturn(List.of());
        when(departmentService.getPrimaryDepartmentName(1L)).thenReturn(null);

        com.superprogrammer.auth.dto.UserVO vo = authService.updateProfile(1L, "老名字", "   ");

        assertNull(vo.getRemark());
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

    // ===== 12x B2 渐进式滑块接线 =====

    @Test
    void login_wrongPassword_recordsCaptchaFailure() {
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(testUser);
        when(passwordEncoder.matches("password123", testUser.getPassword())).thenReturn(false);

        assertThrows(BusinessException.class, () -> authService.login(loginRequest));

        verify(captchaGuard).check("login", "testuser", null);
        verify(captchaGuard).recordFailure("login", "testuser");
        verify(captchaGuard, never()).clear(anyString(), anyString());
    }

    @Test
    void login_success_clearsCaptchaGuard() {
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(testUser);
        when(passwordEncoder.matches("password123", testUser.getPassword())).thenReturn(true);
        when(systemSettingService.getAccessTokenExpirationMs()).thenReturn(300000L);
        when(jwtUtil.generateAccessToken(eq(1L), eq("testuser"), anyList(), eq(300000L), any())).thenReturn("access-token");
        when(jwtUtil.generateRefreshToken(eq(1L), any())).thenReturn("refresh-token");
        when(userMapper.selectRoleCodesByUsername("testuser")).thenReturn(Arrays.asList("user"));
        when(userMapper.selectPermissionCodesByUserId(1L)).thenReturn(Arrays.asList("agent:read"));
        when(userMapper.updateById(any(User.class))).thenReturn(1);

        authService.login(loginRequest);

        verify(captchaGuard).clear("login", "testuser");
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
    // AC-SEC-FR-001（修复III E1 12x#2 演进）：前置闸命中 → 落库锁号才显详细话术；
    // 未落库（本用例查库 miss）维持固定话术——绝不泄露账号存在性
    @Test
    void login_accountLocked_notInDb_fixedMessage() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("login:fail:u:testuser")).thenReturn("5");
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        BusinessException e = assertThrows(BusinessException.class, () -> authService.login(loginRequest));

        assertEquals(40103, e.getCode());   // LOGIN_LOCKED
        assertEquals(ErrorCode.LOGIN_LOCKED.getMessage(), e.getMessage());   // 固定话术
        assertNull(e.getData());
        verify(bizMetrics).authLoginLocked("account");
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
        // Phase4 原子闸：setIfAbsent 抢占赢家（null=抢输会被当重放拒）
        when(valueOperations.setIfAbsent(eq("token:blacklist:jti-1"), eq("rotated"), eq(600000L), eq(TimeUnit.MILLISECONDS)))
                .thenReturn(true);
        when(jwtUtil.generateRefreshToken(1L, "sid-1")).thenReturn("new-refresh");

        TokenResponse response = authService.refreshToken(request);

        assertEquals("new-access", response.getAccessToken());
        assertEquals("new-refresh", response.getRefreshToken());
        // 旧票拉黑（原子抢占）：值=rotated（区别于 logout 的 "1"），TTL=剩余有效期
        verify(valueOperations).setIfAbsent(eq("token:blacklist:jti-1"), eq("rotated"), eq(600000L), eq(TimeUnit.MILLISECONDS));
        verify(bizMetrics).authRefreshRotated();
    }

    // Phase4：并发双发同票旋转——第二个请求 setIfAbsent 抢输 → 按重放拒绝，不发新票
    @Test
    void refreshToken_concurrentRotationLosesRace_rejected() {
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
        // 抢输：拉黑位已被并发请求占住
        when(valueOperations.setIfAbsent(eq("token:blacklist:jti-1"), eq("rotated"), eq(600000L), eq(TimeUnit.MILLISECONDS)))
                .thenReturn(false);

        BusinessException e = assertThrows(BusinessException.class, () -> authService.refreshToken(request));

        assertEquals(ErrorCode.TOKEN_INVALID.getCode(), e.getCode());
        verify(jwtUtil, never()).generateRefreshToken(anyLong(), anyString());   // 不签新票
        verify(bizMetrics).authRefreshReplayed();
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
        // Phase4 原子消费：setIfAbsent 抢占赢家
        when(valueOperations.setIfAbsent(eq("token:blacklist:mfa-jti"), eq("1"), eq(300L), eq(TimeUnit.SECONDS)))
                .thenReturn(true);

        TokenResponse response = authService.verifyMfa(request);

        assertEquals("access-token", response.getAccessToken());
        assertNull(response.getMfaRequired());   // 完整登录响应
        // 一次性（原子）：setIfAbsent 抢占 jti 拉黑位 5min（= 票自然寿命）
        verify(valueOperations).setIfAbsent(eq("token:blacklist:mfa-jti"), eq("1"), eq(300L), eq(TimeUnit.SECONDS));
        verify(bizMetrics).authMfaVerify("success");
    }

    // Phase4：并发重放——验码成功但 setIfAbsent 抢输（同票已被并发消费）→ TOKEN_INVALID + 审计重放
    @Test
    void verifyMfa_concurrentReplayLosesRace_rejected() {
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
        // 抢输：同票已被并发请求消费（拉黑位已存在）
        when(valueOperations.setIfAbsent(eq("token:blacklist:mfa-jti"), eq("1"), eq(300L), eq(TimeUnit.SECONDS)))
                .thenReturn(false);

        BusinessException e = assertThrows(BusinessException.class, () -> authService.verifyMfa(request));

        assertEquals(ErrorCode.TOKEN_INVALID.getCode(), e.getCode());
        verify(userMapper, never()).selectById(anyLong());   // 不进发 token 链
        verify(jwtUtil, never()).generateAccessToken(anyLong(), anyString(), anyList(), anyLong(), any());
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

    // ===== 安全体系 S5 · SEC-FR-100（J2 注销）：密码确认 → 软删匿名化 =====

    @Test
    void deleteAccount_success_anonymizesKicksAndBlacklists() {
        when(userMapper.selectById(1L)).thenReturn(testUser);
        when(passwordEncoder.matches("password123", testUser.getPassword())).thenReturn(true);
        when(passwordEncoder.encode(anyString())).thenReturn("$2a$10$random-overwrite");
        when(jwtUtil.isTokenValid("access-token")).thenReturn(true);
        when(jwtUtil.getTokenId("access-token")).thenReturn("access-jti");
        when(jwtUtil.getRemainingTtl("access-token")).thenReturn(50000L);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        assertDoesNotThrow(() ->
                authService.deleteAccount(1L, "password123", "access-token", null));

        org.mockito.ArgumentCaptor<User> captor = org.mockito.ArgumentCaptor.forClass(User.class);
        verify(userMapper).updateById(captor.capture());
        User saved = captor.getValue();
        assertTrue(saved.getUsername().startsWith("deleted_"), "username 匿名化为 deleted_ 前缀");
        assertTrue(saved.getEmail().endsWith("@deleted.invalid"), "email 匿名化为保留域");
        assertEquals("DELETED", saved.getStatus());
        assertNull(saved.getAvatar());
        assertNull(saved.getPhone());
        assertNull(saved.getWechatUnionid());
        assertNull(saved.getDingtalkUnionId());
        assertEquals("$2a$10$random-overwrite", saved.getPassword(), "随机口令覆盖");
        // 注销语义：踢全部会话 + 当前 token 拉黑 + TOTP 材料清痕
        verify(sessionService).kickAllSessions(1L);
        verify(valueOperations, times(1)).set(startsWith("token:blacklist:"), eq("1"),
                anyLong(), eq(TimeUnit.MILLISECONDS));
        verify(mfaService).purgeForDeletedUser(1L);
    }

    @Test
    void deleteAccount_wrongPassword_rejectedNoMutation() {
        when(userMapper.selectById(1L)).thenReturn(testUser);
        when(passwordEncoder.matches("wrong-pass", testUser.getPassword())).thenReturn(false);

        BusinessException e = assertThrows(BusinessException.class, () ->
                authService.deleteAccount(1L, "wrong-pass", "access-token", null));

        assertEquals(400, e.getCode());
        verify(userMapper, never()).updateById(any(User.class));
        verify(sessionService, never()).kickAllSessions(anyLong());
        verify(valueOperations, never()).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
    }

    @Test
    void deleteAccount_notActive_rejected() {
        testUser.setStatus("BANNED");
        when(userMapper.selectById(1L)).thenReturn(testUser);

        BusinessException e = assertThrows(BusinessException.class, () ->
                authService.deleteAccount(1L, "password123", null, null));

        assertEquals(400, e.getCode());
        verify(userMapper, never()).updateById(any(User.class));
    }

    @Test
    void login_withEmailCredential_success() {
        // 12x 邮箱登录：EMAIL 凭证（大小写不敏感）解析出账号 → 同密码登录；计数归并规范账号键
        com.superprogrammer.auth.entity.UserCredential cred = new com.superprogrammer.auth.entity.UserCredential();
        cred.setUserId(1L);
        cred.setCredentialType(com.superprogrammer.auth.entity.UserCredential.TYPE_EMAIL);
        when(credentialService.findForLoginIgnoreCase(
                eq(com.superprogrammer.auth.entity.UserCredential.TYPE_EMAIL), anyString())).thenReturn(cred);
        when(userMapper.selectById(1L)).thenReturn(testUser);
        when(passwordEncoder.matches("password123", testUser.getPassword())).thenReturn(true);
        when(systemSettingService.getAccessTokenExpirationMs()).thenReturn(300000L);
        when(jwtUtil.generateAccessToken(eq(1L), eq("testuser"), anyList(), eq(300000L), any())).thenReturn("access-token");
        when(jwtUtil.generateRefreshToken(eq(1L), any())).thenReturn("refresh-token");
        when(userMapper.selectRoleCodesByUsername("testuser")).thenReturn(Arrays.asList("user"));
        when(userMapper.selectPermissionCodesByUserId(1L)).thenReturn(Arrays.asList("agent:read"));
        when(userMapper.updateById(any(User.class))).thenReturn(1);
        loginRequest.setUsername("Test@Example.com");

        TokenResponse response = authService.login(loginRequest);

        assertNotNull(response);
        assertEquals("access-token", response.getAccessToken());
        // 失败计数/滑块归并规范账号键（非邮箱原文键）
        verify(captchaGuard).clear("login", "testuser");
    }

    @Test
    void login_withUsersEmailColumnFallback_success() {
        // 12x 邮箱登录：无 EMAIL 凭证 → users.email 列兜底（存量账号），大小写不敏感
        when(credentialService.findForLoginIgnoreCase(anyString(), anyString())).thenReturn(null);
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(testUser);
        when(passwordEncoder.matches("password123", testUser.getPassword())).thenReturn(true);
        when(systemSettingService.getAccessTokenExpirationMs()).thenReturn(300000L);
        when(jwtUtil.generateAccessToken(eq(1L), eq("testuser"), anyList(), eq(300000L), any())).thenReturn("access-token");
        when(jwtUtil.generateRefreshToken(eq(1L), any())).thenReturn("refresh-token");
        when(userMapper.selectRoleCodesByUsername("testuser")).thenReturn(Arrays.asList("user"));
        when(userMapper.selectPermissionCodesByUserId(1L)).thenReturn(Arrays.asList("agent:read"));
        when(userMapper.updateById(any(User.class))).thenReturn(1);
        loginRequest.setUsername("TEST@example.COM");

        TokenResponse response = authService.login(loginRequest);

        assertNotNull(response);
        assertEquals("access-token", response.getAccessToken());
    }

    @Test
    void login_withEmail_wrongPassword_recordsCanonicalKey() {
        // 12x 邮箱登录：密码错 → 失败/滑块计数挂规范账号键 testuser（防换标识摊薄绕过账号锁）
        com.superprogrammer.auth.entity.UserCredential cred = new com.superprogrammer.auth.entity.UserCredential();
        cred.setUserId(1L);
        cred.setCredentialType(com.superprogrammer.auth.entity.UserCredential.TYPE_EMAIL);
        when(credentialService.findForLoginIgnoreCase(anyString(), anyString())).thenReturn(cred);
        when(userMapper.selectById(1L)).thenReturn(testUser);
        when(passwordEncoder.matches("password123", testUser.getPassword())).thenReturn(false);
        loginRequest.setUsername("test@example.com");

        assertThrows(BusinessException.class, () -> authService.login(loginRequest));

        verify(captchaGuard).recordFailure("login", "testuser");
    }

    // ===== 修复III E1（12x#2）：锁定话术可见 + 管理员解锁 =====

    @Test
    void login_accountLockedInDb_showsUnlockTime() {
        // 前置闸命中（Redis 计数≥5）+ DB 暴破锁号（LOCKED+locked_until）→ 详细话术 + data.lockedUntil
        testUser.setStatus("LOCKED");
        testUser.setLockedUntil(OffsetDateTime.now().plusMinutes(15));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("login:fail:u:testuser")).thenReturn("5");
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(testUser);

        BusinessException e = assertThrows(BusinessException.class, () -> authService.login(loginRequest));

        assertEquals(40103, e.getCode());
        assertTrue(e.getMessage().contains("自动解锁"), "话术应含自动解锁时间: " + e.getMessage());
        assertTrue(e.getMessage().contains("联系管理员"));
        assertNotNull(e.getData());
        assertNotNull(e.getData().get("lockedUntil"));
    }

    @Test
    void login_passwordOkButAccountLocked_showsUnlockTime() {
        // Redis 计数已清（降级/过期）但 DB 仍锁 → 密码验过（本人带密码尝试）→ status 分支详细话术
        testUser.setStatus("LOCKED");
        testUser.setLockedUntil(OffsetDateTime.now().plusMinutes(10));
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(testUser);
        when(passwordEncoder.matches("password123", testUser.getPassword())).thenReturn(true);

        BusinessException e = assertThrows(BusinessException.class, () -> authService.login(loginRequest));

        assertEquals(40103, e.getCode());
        assertTrue(e.getMessage().contains("自动解锁"));
        assertNotNull(e.getData().get("lockedUntil"));
    }

    @Test
    void login_banned_keepsUnifiedMessage() {
        // 封禁/禁用不显锁定话术（语义分离——管理端走「启用」不走「解锁」）
        testUser.setStatus("BANNED");
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(testUser);
        when(passwordEncoder.matches("password123", testUser.getPassword())).thenReturn(true);

        BusinessException e = assertThrows(BusinessException.class, () -> authService.login(loginRequest));

        assertEquals("用户已被禁用或锁定", e.getMessage());
        assertNull(e.getData());
    }

    @Test
    void unlockUser_autoLocked_clearsDbRedisBan() {
        testUser.setStatus("LOCKED");
        testUser.setLockedUntil(OffsetDateTime.now().plusMinutes(15));
        when(userMapper.selectById(1L)).thenReturn(testUser);
        when(userMapper.update(isNull(), any())).thenReturn(1);

        authService.unlockUser(1L);

        verify(userMapper).update(isNull(), argThat(w -> true));
        verify(redisTemplate).delete("login:fail:u:testuser");
        verify(banService).restore(1L);
    }

    @Test
    void unlockUser_banned_rejected400() {
        testUser.setStatus("BANNED");
        when(userMapper.selectById(1L)).thenReturn(testUser);

        BusinessException e = assertThrows(BusinessException.class, () -> authService.unlockUser(1L));

        assertEquals(400, e.getCode());
        assertTrue(e.getMessage().contains("启用"));
        verify(banService, never()).restore(anyLong());
    }

    @Test
    void unlockUser_manualLockedWithoutUntil_rejected400() {
        // 管理员手动锁定（无 locked_until）不走解锁——语义分离防绕过
        testUser.setStatus("LOCKED");
        testUser.setLockedUntil(null);
        when(userMapper.selectById(1L)).thenReturn(testUser);

        BusinessException e = assertThrows(BusinessException.class, () -> authService.unlockUser(1L));

        assertEquals(400, e.getCode());
    }
}
