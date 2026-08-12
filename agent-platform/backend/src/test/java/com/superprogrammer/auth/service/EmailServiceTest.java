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
    private AliyunMailConfig mailConfig;
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
        service = new EmailService(mailConfig, redisTemplate, credentialService);
        ReflectionTestUtils.setField(service, "emailEnabled", true);
        ReflectionTestUtils.setField(service, "verifyUrl", "https://test.com/verify-email");
        ReflectionTestUtils.setField(service, "resetUrl", "https://test.com/reset-password");
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        lenient().when(mailConfig.getAccessKeyId()).thenReturn("test-ak");
    }

    @Test
    void sendVerifyEmail_notEnabled_returnsFalse() {
        ReflectionTestUtils.setField(service, "emailEnabled", false);
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
}
