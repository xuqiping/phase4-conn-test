// agent-platform/backend/src/test/java/com/superprogrammer/auth/service/SmsServiceTest.java
package com.superprogrammer.auth.service;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.superprogrammer.auth.config.AliyunSmsConfig;
import com.superprogrammer.auth.dto.TokenResponse;
import com.superprogrammer.auth.entity.User;
import com.superprogrammer.auth.entity.UserCredential;
import com.superprogrammer.auth.mapper.RoleMapper;
import com.superprogrammer.auth.mapper.UserMapper;
import com.superprogrammer.auth.mapper.UserRoleMapper;
import com.superprogrammer.common.audit.AuditLogService;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SmsService 单测（Chunk C）。
 * 覆盖：发码（未开启/手机格式/限流/已发未用）/ 验码（错误/过期/作废/重放/新号建号/老号登录）。
 */
@ExtendWith(MockitoExtension.class)
class SmsServiceTest {

    @Mock private AuthChannelSettingService channelSettings;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;
    @Mock private CredentialService credentialService;
    @Mock private CaptchaService captchaService;
    @Mock private AuthService authService;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private UserMapper userMapper;
    @Mock private RoleMapper roleMapper;
    @Mock private UserRoleMapper userRoleMapper;
    @Mock private AuditLogService auditLogService;

    private SmsService service;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(
                new com.baomidou.mybatisplus.core.MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, User.class);
        TableInfoHelper.initTableInfo(assistant, com.superprogrammer.auth.entity.Role.class);
        TableInfoHelper.initTableInfo(assistant, UserCredential.class);
        TableInfoHelper.initTableInfo(assistant, com.superprogrammer.auth.entity.UserRole.class);
    }

    @BeforeEach
    void setUp() {
        service = new SmsService(channelSettings, redisTemplate, credentialService, captchaService,
                authService, passwordEncoder, userMapper, roleMapper, userRoleMapper, auditLogService);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        lenient().when(channelSettings.smsSnapshot()).thenReturn(new AuthChannelSettingService.SmsSnapshot(
                true, "cn-hangzhou", "test-ak", "test-sk", "测试签名", "SMS_TEST", "SMS_RESET", 5, 10, 30));
    }

    @Test
    void sendCode_notEnabled_throws() {
        when(channelSettings.smsSnapshot()).thenReturn(new AuthChannelSettingService.SmsSnapshot(
                false, "cn-hangzhou", "test-ak", "test-sk", "测试签名", "SMS_TEST", "SMS_RESET", 5, 10, 30));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.sendCode("13800138000", "captcha-token", "127.0.0.1"));
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), ex.getCode());
    }

    @Test
    void sendCode_invalidPhone_throws() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.sendCode("+85212345678", "captcha-token", "127.0.0.1"));
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), ex.getCode());
    }

    // 12x-1 C4：同号 5min 未消费 → 429 + retryAfterSeconds（不再 200 文案伪装成功）
    @Test
    void sendCode_codeActive_throws429WithRetryAfter() {
        when(valueOps.increment(anyString())).thenReturn(1L);
        when(redisTemplate.hasKey("sms:code:13800138000")).thenReturn(true);
        when(redisTemplate.getExpire(eq("sms:code:13800138000"), any())).thenReturn(213L);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.sendCode("13800138000", "captcha-token", "127.0.0.1"));
        assertEquals(ErrorCode.RATE_LIMIT.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("213"), "话术含真实剩余秒: " + ex.getMessage());
        assertEquals(213L, ex.getData().get("retryAfterSeconds"));
        // FAIL 审计行由 sendCode catch 统一记（reason=话术，含剩余秒）
        verify(auditLogService).fromMdc(eq("auth"), eq("sms_code_send"), eq("user"), isNull(),
                argThat((java.util.Map<String, Object> m) -> "13800138000".equals(m.get("phone"))
                        && String.valueOf(m.get("reason")).contains("213")), eq("FAIL"));
    }

    @Test
    void sendCode_valid_sendsCode() {
        when(valueOps.increment(anyString())).thenReturn(1L);
        when(redisTemplate.hasKey(anyString())).thenReturn(false);

        // sendSms 内部 new DefaultAcsClient 会调阿里云，这里无法 mock，预期发送失败但流程正确
        // 由于 sendSms 调真实阿里云 SDK 会抛异常（无网络/AK 无效），sendCode 应抛 INTERNAL_ERROR
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.sendCode("13800138000", "captcha-token", "127.0.0.1"));
        // 阿里云发送失败 → 删 Redis 码 + 抛 INTERNAL_ERROR
        assertEquals(ErrorCode.INTERNAL_ERROR.getCode(), ex.getCode());
        verify(captchaService).verify("captcha-token");
        // B1（8x-1）：发送失败 → sms_code_send FAIL + phone/ip/reason
        verify(auditLogService).fromMdc(eq("auth"), eq("sms_code_send"), eq("user"), isNull(),
                argThat((java.util.Map<String, Object> m) -> "13800138000".equals(m.get("phone")) && "127.0.0.1".equals(m.get("ip"))
                        && m.containsKey("reason")), eq("FAIL"));
    }

    @Test
    void verifyAndLogin_codeExpired_throws() {
        when(valueOps.get("sms:code:13800138000")).thenReturn(null);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.verifyAndLogin("13800138000", "123456"));
        assertEquals(ErrorCode.SMS_CODE_INVALID.getCode(), ex.getCode());
    }

    @Test
    void verifyAndLogin_codeMismatch_incrementsFailCount() {
        when(valueOps.get("sms:code:13800138000")).thenReturn("999999");
        when(valueOps.get("sms:code:fail:13800138000")).thenReturn("0");
        when(valueOps.increment("sms:code:fail:13800138000")).thenReturn(1L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.verifyAndLogin("13800138000", "123456"));
        assertEquals(ErrorCode.SMS_CODE_INVALID.getCode(), ex.getCode());
    }

    @Test
    void verifyAndLogin_codeMismatchFiveTimes_invalidatesCode() {
        when(valueOps.get("sms:code:13800138000")).thenReturn("999999");
        when(valueOps.get("sms:code:fail:13800138000")).thenReturn("5");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.verifyAndLogin("13800138000", "123456"));
        assertEquals(ErrorCode.SMS_CODE_INVALID.getCode(), ex.getCode());
        verify(redisTemplate).delete("sms:code:13800138000");
        verify(redisTemplate).delete("sms:code:fail:13800138000");
    }

    @Test
    void verifyAndLogin_validCodeNewUser_createsAccount() {
        when(valueOps.get("sms:code:13800138000")).thenReturn("123456");
        when(valueOps.get("sms:code:fail:13800138000")).thenReturn("0");
        when(credentialService.findForLogin(UserCredential.TYPE_PHONE, "13800138000")).thenReturn(null);
        // 新号建号：userMapper.insert 后 user.getId() 可用
        doAnswer(invocation -> {
            User u = invocation.getArgument(0);
            u.setId(1L);
            return 1;
        }).when(userMapper).insert(any(User.class));
        when(authService.issueTokensForSms(any(User.class))).thenReturn(TokenResponse.builder()
                .accessToken("jwt-token").refreshToken("rf").tokenType("Bearer").expiresIn(900000L).build());

        TokenResponse response = service.verifyAndLogin("13800138000", "123456");

        assertNotNull(response);
        assertEquals("jwt-token", response.getAccessToken());
        verify(credentialService).createCredential(eq(1L), eq(UserCredential.TYPE_PHONE), eq("13800138000"), isNull(), eq(true));
        verify(redisTemplate).delete("sms:code:13800138000");
        // B1（8x-1）：验码登录成功 → sms_login SUCCESS + targetId + phone
        verify(auditLogService).fromMdc(eq("auth"), eq("sms_login"), eq("user"), eq("1"),
                argThat((java.util.Map<String, Object> m) -> "13800138000".equals(m.get("phone"))), eq("SUCCESS"));
    }

    @Test
    void verifyAndLogin_validCodeExistingUser_logsIn() {
        when(valueOps.get("sms:code:13800138000")).thenReturn("123456");
        when(valueOps.get("sms:code:fail:13800138000")).thenReturn("0");
        UserCredential credential = new UserCredential();
        credential.setUserId(1L);
        credential.setCredentialType(UserCredential.TYPE_PHONE);
        when(credentialService.findForLogin(UserCredential.TYPE_PHONE, "13800138000")).thenReturn(credential);
        User existingUser = new User();
        existingUser.setId(1L);
        existingUser.setUsername("13800138000");
        existingUser.setStatus("ACTIVE");
        when(userMapper.selectById(1L)).thenReturn(existingUser);
        when(authService.issueTokensForSms(any(User.class))).thenReturn(TokenResponse.builder()
                .accessToken("jwt-token").build());

        TokenResponse response = service.verifyAndLogin("13800138000", "123456");

        assertNotNull(response);
        verify(credentialService, never()).createCredential(anyLong(), anyString(), anyString(), any(), anyBoolean());
    }

    @Test
    void verifyAndLogin_disabledUser_throws() {
        when(valueOps.get("sms:code:13800138000")).thenReturn("123456");
        when(valueOps.get("sms:code:fail:13800138000")).thenReturn("0");
        UserCredential credential = new UserCredential();
        credential.setUserId(1L);
        when(credentialService.findForLogin(UserCredential.TYPE_PHONE, "13800138000")).thenReturn(credential);
        User disabledUser = new User();
        disabledUser.setId(1L);
        disabledUser.setStatus("DISABLED");
        when(userMapper.selectById(1L)).thenReturn(disabledUser);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.verifyAndLogin("13800138000", "123456"));
        assertEquals(ErrorCode.UNAUTHORIZED.getCode(), ex.getCode());
    }

    /**
     * 修复VIII B3（VIII-4）：验证码明文不进日志——发码全链路（含 sendSms 失败分支）捕获日志，
     * 断言 Redis 里那份 6 位码不出现在任何日志行。真码调试口径=查 Redis，不放宽日志。
     * （SmsService.java 原 code={} 是阿里云应答状态码非验证码，已改名 respCode= 消除误读。）
     */
    @Test
    void sendCodeFlow_neverLogsCleartextCode() throws Exception {
        when(valueOps.increment(anyString())).thenReturn(1L);
        when(redisTemplate.hasKey(anyString())).thenReturn(false);

        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(SmsService.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            // 无网络环境 sendSms 必失败（INTERNAL_ERROR），但验证码已生成并写 Redis——足够覆盖日志面
            assertThrows(BusinessException.class,
                    () -> service.sendCode("13800138000", "captcha-token", "127.0.0.1"));
        } finally {
            logger.detachAppender(appender);
        }

        // 捕获写入 Redis 的 6 位码
        org.mockito.ArgumentCaptor<String> codeCap = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(eq("sms:code:13800138000"), codeCap.capture(), anyLong(), any());
        String code = codeCap.getValue();
        assertTrue(code != null && code.matches("\\d{6}"), "验证码应为 6 位数字: " + code);

        String logs = appender.list.stream()
                .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                .collect(java.util.stream.Collectors.joining("\n"));
        assertFalse(logs.contains(code), "验证码明文不得进日志。捕获日志:\n" + logs);
    }
}
