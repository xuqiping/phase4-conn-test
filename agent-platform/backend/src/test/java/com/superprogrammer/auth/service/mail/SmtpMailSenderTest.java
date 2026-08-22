package com.superprogrammer.auth.service.mail;

import com.superprogrammer.auth.service.AuthChannelSettingService;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import static org.junit.jupiter.api.Assertions.*;

/** SmtpMailSender 单测（12x）：配置→JavaMailSenderImpl 属性映射 + 失败降级返 false。 */
class SmtpMailSenderTest {

    private final SmtpMailSender sender = new SmtpMailSender();

    @Test
    void buildSender_sslTrue_usesSslAndCustomPort() {
        var smtp = new AuthChannelSettingService.Smtp("smtp.qq.com", 465, true, "me@qq.com", "authcode16", "平台");
        JavaMailSenderImpl impl = sender.buildSender(smtp);
        assertEquals("smtp.qq.com", impl.getHost());
        assertEquals(465, impl.getPort());
        assertEquals("me@qq.com", impl.getUsername());
        assertEquals("authcode16", impl.getPassword());
        assertEquals("true", impl.getJavaMailProperties().getProperty("mail.smtp.ssl.enable"));
        assertNull(impl.getJavaMailProperties().getProperty("mail.smtp.starttls.enable"));
        assertEquals("true", impl.getJavaMailProperties().getProperty("mail.smtp.auth"));
        assertEquals("5000", impl.getJavaMailProperties().getProperty("mail.smtp.connectiontimeout"));
    }

    @Test
    void buildSender_sslFalse_usesStartTls() {
        var smtp = new AuthChannelSettingService.Smtp("smtp.exmail.qq.com", 587, false, "n@corp.com", "pwd", null);
        JavaMailSenderImpl impl = sender.buildSender(smtp);
        assertEquals(587, impl.getPort());
        assertEquals("true", impl.getJavaMailProperties().getProperty("mail.smtp.starttls.enable"));
        assertNull(impl.getJavaMailProperties().getProperty("mail.smtp.ssl.enable"));
    }

    @Test
    void buildSender_portZero_fallsBackTo465() {
        var smtp = new AuthChannelSettingService.Smtp("smtp.qq.com", 0, true, "me@qq.com", "x", null);
        assertEquals(465, sender.buildSender(smtp).getPort());
    }

    @Test
    void send_incompleteConfig_returnsFalseWithoutThrow() {
        var cfg = snapshot(new AuthChannelSettingService.Smtp("smtp.qq.com", 465, true, "me@qq.com", "", null));
        assertFalse(sender.send(cfg, "a@b.com", "s", "<p>x</p>"));
        var cfg2 = snapshot(null);
        assertFalse(sender.send(cfg2, "a@b.com", "s", "<p>x</p>"));
    }

    @Test
    void send_unreachableServer_returnsFalse() {
        // 本机 1 端口必拒连（5s 超时内快速失败），验证降级语义
        var cfg = snapshot(new AuthChannelSettingService.Smtp("127.0.0.1", 1, false, "me@qq.com", "x", null));
        assertFalse(sender.send(cfg, "a@b.com", "s", "<p>x</p>"));
    }

    private static AuthChannelSettingService.MailSnapshot snapshot(AuthChannelSettingService.Smtp smtp) {
        return new AuthChannelSettingService.MailSnapshot(
                true, "", "", "", "", "", null, "", "", "SMTP", smtp);
    }
}
