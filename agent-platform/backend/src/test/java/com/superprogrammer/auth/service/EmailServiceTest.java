// agent-platform/backend/src/test/java/com/superprogrammer/auth/service/EmailServiceTest.java
package com.superprogrammer.auth.service;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.superprogrammer.auth.config.AliyunMailConfig;
import com.superprogrammer.auth.dto.CredentialVO;
import com.superprogrammer.auth.entity.UserCredential;
import com.superprogrammer.common.exception.BusinessException;
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

    private EmailService service;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(
                new com.baomidou.mybatisplus.core.MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, UserCredential.class);
    }

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        service = new EmailService(channelSettings, redisTemplate, credentialService, List.of());
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
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
        String msg = service.resendVerifyEmail("a@b.com");
        assertTrue(msg.contains("发送过于频繁"));
    }

    @Test
    void resendVerifyEmail_emailNotRegistered_returnsUnifiedMessage() {
        when(valueOps.increment(anyString())).thenReturn(1L);
        when(credentialService.findForLogin(UserCredential.TYPE_EMAIL, "not@exist.com")).thenReturn(null);
        String msg = service.resendVerifyEmail("not@exist.com");
        assertEquals("若该邮箱已注册且未验证，验证邮件已发送", msg);
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
        service = new EmailService(channelSettings, redisTemplate, credentialService, List.of(aliyun, smtp));

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
        service = new EmailService(channelSettings, redisTemplate, credentialService, List.of(aliyun));

        // enabled=false 也应能测试（先测通再开开关）
        var cfg = new AuthChannelSettingService.MailSnapshot(
                false, "cn-hangzhou", "ak", "sk", "noreply@t.com", "测", null, "", "", "ALIYUN", null);
        when(channelSettings.mailSnapshot()).thenReturn(cfg);

        assertTrue(service.sendTestMail("a@b.com"));
        verify(aliyun).send(eq(cfg), eq("a@b.com"), contains("测试"), anyString());
    }
}
