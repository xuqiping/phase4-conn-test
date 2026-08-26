// agent-platform/backend/src/test/java/com/superprogrammer/auth/service/PasswordResetServiceTest.java
package com.superprogrammer.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.superprogrammer.auth.entity.User;
import com.superprogrammer.auth.entity.UserCredential;
import com.superprogrammer.auth.mapper.UserMapper;
import com.superprogrammer.common.audit.AuditLogService;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * PasswordResetService 单测（Chunk E）。
 * 覆盖：forgot 统一话术（号不存在/未验证邮箱）/ reset token 校验/新旧密码相同拒/踢会话。
 */
@ExtendWith(MockitoExtension.class)
class PasswordResetServiceTest {

    @Mock private UserMapper userMapper;
    @Mock private CredentialService credentialService;
    @Mock private EmailService emailService;
    @Mock private SmsService smsService;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;
    @Mock private SessionService sessionService;
    @Mock private ProgressiveCaptchaGuard captchaGuard;
    @Mock private AuthChannelSettingService channelSettings;
    @Mock private AuditLogService auditLogService;

    private PasswordResetService service;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(
                new com.baomidou.mybatisplus.core.MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, User.class);
        TableInfoHelper.initTableInfo(assistant, UserCredential.class);
    }

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        service = new PasswordResetService(userMapper, credentialService, emailService,
                smsService, passwordEncoder, redisTemplate, sessionService, captchaGuard, channelSettings, auditLogService);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        // 12x 开关回退：默认开（保持原严校验语义）；「关」场景由专项用例覆盖
        lenient().when(channelSettings.isEmailVerificationRequired()).thenReturn(true);
    }

    @Test
    void forgot_userNotFound_returnsUnifiedMessage() {
        when(valueOps.increment(anyString())).thenReturn(1L);
        when(credentialService.findForLogin(anyString(), anyString())).thenReturn(null);
        String msg = service.forgot("nonexistent", "EMAIL", "127.0.0.1", null);
        assertEquals("若账号存在，重置链接/码已发送", msg);
        verify(emailService, never()).sendResetEmail(anyLong(), anyString());
        // B1（8x-1）：探测（号不存在）→ SUCCESS 话术 + hit=false 只进审计
        verify(auditLogService).fromMdc(eq("auth"), eq("password_forgot"), eq("user"), isNull(),
                argThat((java.util.Map<String, Object> m) -> "nonexistent".equals(m.get("identifier")) && Boolean.FALSE.equals(m.get("hit"))),
                eq("SUCCESS"));
    }

    @Test
    void forgot_rateLimited_throws() {
        when(valueOps.increment(anyString())).thenReturn(4L);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.forgot("someone", "EMAIL", "127.0.0.1", null));
        assertEquals(ErrorCode.RATE_LIMIT.getCode(), ex.getCode());
        // B1（8x-1）：限流拒 → password_forgot FAIL + reason（联动表口径）
        verify(auditLogService).fromMdc(eq("auth"), eq("password_forgot"), eq("user"), isNull(),
                argThat((java.util.Map<String, Object> m) -> m.containsKey("reason")), eq("FAIL"));
    }

    @Test
    void reset_invalidToken_throws() {
        when(valueOps.get(EmailService.RESET_TOKEN_PREFIX + "bad")).thenReturn(null);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.reset("bad", "NewPass123!", "EMAIL", null));
        assertEquals(ErrorCode.RESET_TOKEN_INVALID.getCode(), ex.getCode());
        // B1（8x-1）：token 无效 → password_reset FAIL（userId 尚未解出为 null）
        verify(auditLogService).fromMdc(eq("auth"), eq("password_reset"), eq("user"), isNull(),
                argThat((java.util.Map<String, Object> m) -> m.containsKey("reason")), eq("FAIL"));
    }

    @Test
    void reset_validToken_updatesPasswordAndKicksSession() {
        when(valueOps.get(EmailService.RESET_TOKEN_PREFIX + "valid")).thenReturn("1");
        User user = new User();
        user.setId(1L);
        user.setUsername("testuser");
        user.setPassword("$2b$oldhash");
        when(userMapper.selectById(1L)).thenReturn(user);
        when(passwordEncoder.matches("NewPass123!", "$2b$oldhash")).thenReturn(false);
        when(passwordEncoder.encode("NewPass123!")).thenReturn("$2b$newhash");

        service.reset("valid", "NewPass123!", "EMAIL", null);

        verify(userMapper).updateById(any(User.class));
        // Chunk G：踢会话改走 SessionService.kickAllSessions（修原 session: 错前缀 bug）
        verify(sessionService).kickAllSessions(1L);
        verify(redisTemplate).delete(EmailService.RESET_TOKEN_PREFIX + "valid");
        // B1（8x-1）：重置成功 → password_reset SUCCESS + targetId/channel
        verify(auditLogService).fromMdc(eq("auth"), eq("password_reset"), eq("user"), eq("1"),
                argThat((java.util.Map<String, Object> m) -> Long.valueOf(1L).equals(m.get("userId")) && "EMAIL".equals(m.get("channel"))),
                eq("SUCCESS"));
    }

    @Test
    void reset_sameAsOldPassword_throws() {
        when(valueOps.get(EmailService.RESET_TOKEN_PREFIX + "valid")).thenReturn("1");
        User user = new User();
        user.setId(1L);
        user.setUsername("testuser");
        user.setPassword("$2b$oldhash");
        when(userMapper.selectById(1L)).thenReturn(user);
        when(passwordEncoder.matches("OldPass123!", "$2b$oldhash")).thenReturn(true);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.reset("valid", "OldPass123!", "EMAIL", null));
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), ex.getCode());
    }

    @Test
    void reset_validTokenWithVerifiedEmail_sendsAlertMail() {
        // 12x B4：重置成功 → 给已验证邮箱发告警信
        when(valueOps.get(EmailService.RESET_TOKEN_PREFIX + "valid")).thenReturn("1");
        User user = new User();
        user.setId(1L);
        user.setUsername("testuser");
        user.setPassword("$2b$oldhash");
        when(userMapper.selectById(1L)).thenReturn(user);
        when(passwordEncoder.matches("NewPass123!", "$2b$oldhash")).thenReturn(false);
        when(passwordEncoder.encode("NewPass123!")).thenReturn("$2b$newhash");
        com.superprogrammer.auth.entity.UserCredential emailCred =
                new com.superprogrammer.auth.entity.UserCredential();
        emailCred.setCredentialType(com.superprogrammer.auth.entity.UserCredential.TYPE_EMAIL);
        emailCred.setIdentifier("victim@x.com");
        emailCred.setVerified(true);
        when(credentialService.findByUserIdRaw(1L)).thenReturn(java.util.List.of(emailCred));

        service.reset("valid", "NewPass123!", "EMAIL", null);

        verify(emailService).sendPasswordResetAlertEmail(eq("victim@x.com"), anyString());
    }

    @Test
    void reset_noVerifiedEmail_skipsAlertMail() {
        // 12x B4：无已验证邮箱 → 不发告警但不影响重置
        when(valueOps.get(EmailService.RESET_TOKEN_PREFIX + "valid")).thenReturn("1");
        User user = new User();
        user.setId(1L);
        user.setUsername("testuser");
        user.setPassword("$2b$oldhash");
        when(userMapper.selectById(1L)).thenReturn(user);
        when(passwordEncoder.matches("NewPass123!", "$2b$oldhash")).thenReturn(false);
        when(passwordEncoder.encode("NewPass123!")).thenReturn("$2b$newhash");

        service.reset("valid", "NewPass123!", "EMAIL", null);

        verify(emailService, never()).sendPasswordResetAlertEmail(anyString(), anyString());
    }

    @Test
    void forgot_unverifiedEmailSwitchOff_sendsResetMail() {
        // 12x 开关回退：验证总开关=关 → 未验证 EMAIL 凭证也可收重置信
        lenient().when(channelSettings.isEmailVerificationRequired()).thenReturn(false);
        when(valueOps.increment(anyString())).thenReturn(1L);
        User user = new User();
        user.setId(1L);
        user.setUsername("testuser");
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(user);
        UserCredential emailCred = new UserCredential();
        emailCred.setCredentialType(UserCredential.TYPE_EMAIL);
        emailCred.setIdentifier("unv@x.com");
        emailCred.setVerified(false);
        when(credentialService.findByUserIdRaw(1L)).thenReturn(java.util.List.of(emailCred));

        String msg = service.forgot("testuser", "EMAIL", "127.0.0.1", null);

        assertEquals("若账号存在，重置链接/码已发送", msg);
        verify(emailService).sendResetEmail(1L, "unv@x.com");
        // B1（8x-1）：命中账号并发信 → hit=true
        verify(auditLogService).fromMdc(eq("auth"), eq("password_forgot"), eq("user"), isNull(),
                argThat((java.util.Map<String, Object> m) -> Boolean.TRUE.equals(m.get("hit")) && !m.containsKey("reason")), eq("SUCCESS"));
    }

    @Test
    void forgot_usersEmailFallbackSwitchOff_sendsResetMail() {
        // 12x 开关回退：无 EMAIL 凭证 → users.email 列兜底收信
        lenient().when(channelSettings.isEmailVerificationRequired()).thenReturn(false);
        when(valueOps.increment(anyString())).thenReturn(1L);
        User user = new User();
        user.setId(1L);
        user.setUsername("testuser");
        user.setEmail("legacy@x.com");
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(user);
        when(credentialService.findByUserIdRaw(1L)).thenReturn(java.util.List.of());

        String msg = service.forgot("testuser", "EMAIL", "127.0.0.1", null);

        assertEquals("若账号存在，重置链接/码已发送", msg);
        verify(emailService).sendResetEmail(1L, "legacy@x.com");
    }

    @Test
    void forgot_unverifiedEmailSwitchOn_neverSends() {
        // 验证总开关=开 → 未验证 EMAIL 凭证不收信（原严语义保留）
        when(valueOps.increment(anyString())).thenReturn(1L);
        User user = new User();
        user.setId(1L);
        user.setUsername("testuser");
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(user);
        UserCredential emailCred = new UserCredential();
        emailCred.setCredentialType(UserCredential.TYPE_EMAIL);
        emailCred.setIdentifier("unv@x.com");
        emailCred.setVerified(false);
        when(credentialService.findByUserIdRaw(1L)).thenReturn(java.util.List.of(emailCred));

        service.forgot("testuser", "EMAIL", "127.0.0.1", null);

        verify(emailService, never()).sendResetEmail(anyLong(), anyString());
    }
}
