// agent-platform/backend/src/test/java/com/superprogrammer/auth/service/PasswordResetServiceTest.java
package com.superprogrammer.auth.service;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.superprogrammer.auth.entity.User;
import com.superprogrammer.auth.entity.UserCredential;
import com.superprogrammer.auth.mapper.UserMapper;
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
                smsService, passwordEncoder, redisTemplate, sessionService, captchaGuard);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    @Test
    void forgot_userNotFound_returnsUnifiedMessage() {
        when(valueOps.increment(anyString())).thenReturn(1L);
        when(credentialService.findForLogin(anyString(), anyString())).thenReturn(null);
        String msg = service.forgot("nonexistent", "EMAIL", "127.0.0.1", null);
        assertEquals("若账号存在，重置链接/码已发送", msg);
        verify(emailService, never()).sendResetEmail(anyLong(), anyString());
    }

    @Test
    void forgot_rateLimited_throws() {
        when(valueOps.increment(anyString())).thenReturn(4L);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.forgot("someone", "EMAIL", "127.0.0.1", null));
        assertEquals(ErrorCode.RATE_LIMIT.getCode(), ex.getCode());
    }

    @Test
    void reset_invalidToken_throws() {
        when(valueOps.get(EmailService.RESET_TOKEN_PREFIX + "bad")).thenReturn(null);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.reset("bad", "NewPass123!", "EMAIL", null));
        assertEquals(ErrorCode.RESET_TOKEN_INVALID.getCode(), ex.getCode());
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
}
