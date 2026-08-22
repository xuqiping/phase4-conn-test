// agent-platform/backend/src/test/java/com/superprogrammer/auth/service/EmailServiceTest.java
package com.superprogrammer.auth.service;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.superprogrammer.auth.config.AliyunMailConfig;
import com.superprogrammer.auth.dto.CredentialVO;
import com.superprogrammer.auth.entity.UserCredential;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

/**
 * EmailService 单测（Chunk B）。
 * 覆盖：发验证邮件（未开启/成功）/激活（token 无效/过期/有效）/重发（限流/统一话术）。
 */
@ExtendWith(MockitoExtension.class)
class EmailServiceTest {

    @Mock
    private AuthChannelSettingService channelSettings;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOps;
    @Mock
    private CredentialService credentialService;
    @Mock
    private com.superprogrammer.auth.service.mail.MailSendQuotaService mailQuota;
    @Mock
    private ProgressiveCaptchaGuard captchaGuard;

    private EmailService service;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(
                new com.baomidou.mybatisplus.core.MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, UserCredential.class);
    }

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        service = new EmailService(channelSettings, redisTemplate, credentialService, mailQuota, captchaGuard, List.of());
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        lenient().when(mailQuota.tryConsumeDaily()).thenReturn(true);
        lenient().when(channelSettings.mailSnapshot()).thenReturn(new AuthChannelSettingService.MailSnapshot(
                true, "cn-hangzhou", "test-ak", "test-sk", "noreply@test.com", "测试",
                null, "https://test.com/verify-email", "https://test.com/reset-password",
                "ALIYUN", null));
    }

    @Test
    void sendVerifyEmail_notEnabled_returnsFalse() {
        when(channelSettings.mailSnapshot()).thenReturn(new AuthChannelSettingService.MailSnapshot(
                false, "cn-hangzhou", "test-ak", "test-sk", "noreply@test.com", "测试",
                null, "https://test.com/verify-email", "https://test.com/reset-password",
                "ALIYUN", null));
        assertFalse(service.sendVerifyEmail(1L, "a@b.com"));
        verifyNoInteractions(valueOps);
    }

    @Test
    void verifyEmail_blankToken_throws() {
        BusinessException ex = assertThrows(BusinessException.class, () -> service.verifyEmail(""));
        assertEquals("链接无效", ex.getMessage());
    }

    @Test
    void verifyEmail_redisMiss_throws() {
        when(valueOps.get(startsWith("verify:email:"))).thenReturn(null);
        BusinessException ex = assertThrows(BusinessException.class, () -> service.verifyEmail("bad-token"));
        assertEquals("链接无效或已过期", ex.getMessage());
    }

    @Test
    void verifyEmail_validToken_marksVerified() {
        when(valueOps.get("verify:email:valid-token")).thenReturn("1");
        when(credentialService.markVerifiedByIdentifier(UserCredential.TYPE_EMAIL, "a@b.com")).thenReturn(true);

        UserCredential emailCredential = mock(UserCredential.class);
        when(emailCredential.getCredentialType()).thenReturn(UserCredential.TYPE_EMAIL);
        when(emailCredential.getIdentifier()).thenReturn("a@b.com");
        when(credentialService.findByUserIdRaw(1L)).thenReturn(List.of(emailCredential));

        assertDoesNotThrow(() -> service.verifyEmail("valid-token"));
        verify(credentialService).markVerifiedByIdentifier(UserCredential.TYPE_EMAIL, "a@b.com");
        verify(redisTemplate).delete("verify:email:valid-token");
    }

    @Test
    void resendVerifyEmail_rateLimited_returnsMessage() {
        when(valueOps.increment(startsWith("resend:email:"))).thenReturn(2L);
        String msg = service.resendVerifyEmail("a@b.com", "1.2.3.4");
        assertTrue(msg.contains("发送过于频繁"));
    }

    @Test
    void resendVerifyEmail_emailNotRegistered_returnsUnifiedMessage() {
        when(valueOps.increment(anyString())).thenReturn(1L);
        when(credentialService.findForLogin(UserCredential.TYPE_EMAIL, "not@exist.com")).thenReturn(null);
        String msg = service.resendVerifyEmail("not@exist.com", "1.2.3.4");
        assertEquals("若该邮箱已注册且未验证，验证邮件已发送", msg);
    }

    @Test
    void resendVerifyEmail_ipQuotaExceeded_throwsRateLimit() {
        doThrow(new BusinessException(ErrorCode.RATE_LIMIT, "发送过于频繁，请稍后再试"))
                .when(mailQuota).checkIpHourly("9.9.9.9");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.resendVerifyEmail("a@b.com", "9.9.9.9"));
        assertEquals(ErrorCode.RATE_LIMIT.getCode(), ex.getCode());
    }

    // ==================== 12x：通道路由 + 测试发信 ====================

    @Test
    void sendVerifyEmail_smtpProviderIncompleteConfig_returnsFalse() {
        when(channelSettings.mailSnapshot()).thenReturn(new AuthChannelSettingService.MailSnapshot(
                true, "", "", "", "", "", null, "", "",
                "SMTP", new AuthChannelSettingService.Smtp("smtp.qq.com", 465, true, "me@qq.com", "", null)));
        assertFalse(service.sendVerifyEmail(1L, "a@b.com"));
        verifyNoInteractions(valueOps);
    }

    @Test
    void sendVerifyEmail_routesToSmtpSender() {
        com.superprogrammer.auth.service.mail.MailSender aliyun = mock(com.superprogrammer.auth.service.mail.MailSender.class);
        com.superprogrammer.auth.service.mail.MailSender smtp = mock(com.superprogrammer.auth.service.mail.MailSender.class);
        when(aliyun.provider()).thenReturn("ALIYUN");
        when(smtp.provider()).thenReturn("SMTP");
        when(smtp.send(any(), anyString(), anyString(), anyString())).thenReturn(true);
        service = new EmailService(channelSettings, redisTemplate, credentialService, mailQuota, captchaGuard, List.of(aliyun, smtp));

        var cfg = new AuthChannelSettingService.MailSnapshot(
                true, "", "", "", "", "", null, "https://t.com/v", "",
                "SMTP", new AuthChannelSettingService.Smtp("smtp.qq.com", 465, true, "me@qq.com", "auth16", null));
        when(channelSettings.mailSnapshot()).thenReturn(cfg);

        assertTrue(service.sendVerifyEmail(1L, "a@b.com"));
        verify(smtp).send(eq(cfg), eq("a@b.com"), anyString(), contains("https://t.com/v?token="));
        verify(aliyun, never()).send(any(), anyString(), anyString(), anyString());
    }

    @Test
    void sendTestMail_notConfigured_returnsFalse() {
        when(channelSettings.mailSnapshot()).thenReturn(new AuthChannelSettingService.MailSnapshot(
                false, "", "", "", "", "", null, "", "", "ALIYUN", null));
        assertFalse(service.sendTestMail("a@b.com"));
    }

    @Test
    void sendTestMail_configured_delegatesToSenderEvenWhenDisabled() {
        com.superprogrammer.auth.service.mail.MailSender aliyun = mock(com.superprogrammer.auth.service.mail.MailSender.class);
        when(aliyun.provider()).thenReturn("ALIYUN");
        when(aliyun.send(any(), anyString(), anyString(), anyString())).thenReturn(true);
        service = new EmailService(channelSettings, redisTemplate, credentialService, mailQuota, captchaGuard, List.of(aliyun));

        // enabled=false 也应能测试（先测通再开开关）
        var cfg = new AuthChannelSettingService.MailSnapshot(
                false, "cn-hangzhou", "ak", "sk", "noreply@t.com", "测", null, "", "", "ALIYUN", null);
        when(channelSettings.mailSnapshot()).thenReturn(cfg);

        assertTrue(service.sendTestMail("a@b.com"));
        verify(aliyun).send(eq(cfg), eq("a@b.com"), contains("测试"), anyString());
    }

    @Test
    void sendMail_dailyCapExhausted_returnsFalse() {
        // B3：日总量封顶 → 委托前即拒（不再调通道）
        com.superprogrammer.auth.service.mail.MailSender aliyun = mock(com.superprogrammer.auth.service.mail.MailSender.class);
        when(aliyun.provider()).thenReturn("ALIYUN");
        when(mailQuota.tryConsumeDaily()).thenReturn(false);
        service = new EmailService(channelSettings, redisTemplate, credentialService, mailQuota, captchaGuard, List.of(aliyun));

        var cfg = new AuthChannelSettingService.MailSnapshot(
                true, "cn-hangzhou", "ak", "sk", "noreply@t.com", "测", null, "", "", "ALIYUN", null);
        when(channelSettings.mailSnapshot()).thenReturn(cfg);

        assertFalse(service.sendVerifyEmail(1L, "a@b.com"));
        verify(aliyun, never()).send(any(), anyString(), anyString(), anyString());
    }

    // ==================== 12x B1：注册邮箱验证码 ====================

    @Test
    void sendRegisterCode_mailDisabled_throws() {
        // B1 强校验：通道未开启 → 注册暂停（宁缺毋滥，不放行无验证注册）
        when(channelSettings.mailSnapshot()).thenReturn(new AuthChannelSettingService.MailSnapshot(
                false, "cn-hangzhou", "ak", "sk", "noreply@t.com", "测", null, "", "", "ALIYUN", null));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.sendRegisterCode("a@b.com", "1.2.3.4", null));
        assertTrue(ex.getMessage().contains("邮件通道未开启"));
    }

    @Test
    void sendRegisterCode_emailAlreadyRegistered_throwsConflict() {
        UserCredential cred = mock(UserCredential.class);
        when(credentialService.findForLogin(UserCredential.TYPE_EMAIL, "taken@b.com")).thenReturn(cred);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.sendRegisterCode("taken@b.com", "1.2.3.4", null));
        assertEquals(ErrorCode.CONFLICT.getCode(), ex.getCode());
    }

    @Test
    void sendRegisterCode_resendWithin60s_throwsRateLimit() {
        when(credentialService.findForLogin(UserCredential.TYPE_EMAIL, "a@b.com")).thenReturn(null);
        when(valueOps.increment("regcode:resend:a@b.com")).thenReturn(2L);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.sendRegisterCode("a@b.com", "1.2.3.4", null));
        assertEquals(ErrorCode.RATE_LIMIT.getCode(), ex.getCode());
    }

    @Test
    void sendRegisterCode_success_storesCodeAndSends() {
        com.superprogrammer.auth.service.mail.MailSender aliyun = mock(com.superprogrammer.auth.service.mail.MailSender.class);
        when(aliyun.provider()).thenReturn("ALIYUN");
        when(aliyun.send(any(), anyString(), anyString(), anyString())).thenReturn(true);
        service = new EmailService(channelSettings, redisTemplate, credentialService, mailQuota, captchaGuard, List.of(aliyun));

        when(credentialService.findForLogin(UserCredential.TYPE_EMAIL, "a@b.com")).thenReturn(null);
        when(valueOps.increment("regcode:resend:a@b.com")).thenReturn(1L);

        assertDoesNotThrow(() -> service.sendRegisterCode("a@b.com", "1.2.3.4", null));

        verify(valueOps).set(eq("regcode:email:a@b.com"), matches("\\d{6}"), eq(600L), any());
        verify(valueOps).set(eq("regcode:try:a@b.com"), eq("0"), eq(600L), any());
        verify(mailQuota).checkIpHourly("1.2.3.4");
        verify(aliyun).send(any(), eq("a@b.com"), contains("注册验证码"), anyString());
    }

    @Test
    void verifyRegisterCode_expired_throws() {
        when(valueOps.get("regcode:email:a@b.com")).thenReturn(null);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.verifyRegisterCode("a@b.com", "123456"));
        assertTrue(ex.getMessage().contains("已过期"));
    }

    @Test
    void verifyRegisterCode_wrongCode_throwsAndCounts() {
        when(valueOps.get("regcode:email:a@b.com")).thenReturn("123456");
        when(valueOps.increment("regcode:try:a@b.com")).thenReturn(1L);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.verifyRegisterCode("a@b.com", "000000"));
        assertTrue(ex.getMessage().contains("验证码错误"));
        verify(redisTemplate, never()).delete("regcode:email:a@b.com");
    }

    @Test
    void verifyRegisterCode_tooManyTries_invalidates() {
        when(valueOps.get("regcode:email:a@b.com")).thenReturn("123456");
        when(valueOps.increment("regcode:try:a@b.com")).thenReturn(6L);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.verifyRegisterCode("a@b.com", "123456"));
        assertTrue(ex.getMessage().contains("错误次数过多"));
        verify(redisTemplate).delete("regcode:email:a@b.com");
        verify(redisTemplate).delete("regcode:try:a@b.com");
    }

    @Test
    void verifyRegisterCode_success_consumesCode() {
        when(valueOps.get("regcode:email:a@b.com")).thenReturn("123456");
        when(valueOps.increment("regcode:try:a@b.com")).thenReturn(1L);
        assertDoesNotThrow(() -> service.verifyRegisterCode("a@b.com", "123456"));
        verify(redisTemplate).delete("regcode:email:a@b.com");
        verify(redisTemplate).delete("regcode:try:a@b.com");
    }
}
