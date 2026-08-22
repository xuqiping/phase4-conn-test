package com.superprogrammer.auth.service.mail;

import com.superprogrammer.auth.service.AuthChannelSettingService;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.util.Properties;

/**
 * 通用 SMTP 发信通道（12x：腾讯 QQ 邮箱 / 腾讯企业邮 / 网易等任意 SMTP 服务）。
 *
 * <p>每次发送按配置快照动态构建 {@link JavaMailSenderImpl}（无状态、配置改完即时生效）；
 * 连接/读写超时 5s，失败记日志返回 false 不阻断主链。
 *
 * <p>腾讯邮箱填法：QQ 邮箱 host=smtp.qq.com port=465 SSL开 用户名=完整邮箱 密码=16位授权码；
 * 腾讯企业邮 host=smtp.exmail.qq.com port=465 SSL开 密码=客户端专用密码。</p>
 */
@Slf4j
@Component
public class SmtpMailSender implements MailSender {

    private static final int DEFAULT_PORT = 465;
    private static final String TIMEOUT_MS = "5000";

    @Override
    public String provider() {
        return "SMTP";
    }

    @Override
    public boolean send(AuthChannelSettingService.MailSnapshot config, String toEmail, String subject, String htmlBody) {
        AuthChannelSettingService.Smtp smtp = config.smtp();
        if (smtp == null || isBlank(smtp.host()) || isBlank(smtp.username()) || isBlank(smtp.password())) {
            log.warn("SMTP 通道未配置完整（host/username/password 缺一），跳过发信 to={}", AliyunMailSender.maskEmail(toEmail));
            return false;
        }
        try {
            JavaMailSenderImpl sender = buildSender(smtp);
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            // 发件人=SMTP 用户名（腾讯等不允许伪装其他 From）+ 昵称
            String fromAlias = isBlank(smtp.fromAlias()) ? "智能体平台" : smtp.fromAlias();
            helper.setFrom(new InternetAddress(smtp.username(), fromAlias, "UTF-8"));
            helper.setTo(toEmail);
            helper.setSubject(subject);
            helper.setText(htmlBody, true);
            sender.send(message);
            log.info("邮件发送成功 channel=SMTP host={} to={} subject={}",
                    smtp.host(), AliyunMailSender.maskEmail(toEmail), subject);
            return true;
        } catch (Exception e) {
            log.error("邮件发送失败 channel=SMTP host={} to={} subject={} : {}",
                    smtp.host(), AliyunMailSender.maskEmail(toEmail), subject, e.toString());
            return false;
        }
    }

    /** 按配置构建 JavaMailSenderImpl（包级可见供单测断言属性）。 */
    JavaMailSenderImpl buildSender(AuthChannelSettingService.Smtp smtp) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(smtp.host().trim());
        int port = smtp.port() > 0 ? smtp.port() : DEFAULT_PORT;
        sender.setPort(port);
        sender.setUsername(smtp.username().trim());
        sender.setPassword(smtp.password());
        sender.setDefaultEncoding("UTF-8");
        Properties props = sender.getJavaMailProperties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.auth", "true");
        if (smtp.ssl()) {
            // 465 等 SSL 直连端口
            props.put("mail.smtp.ssl.enable", "true");
        } else {
            // 587 等 STARTTLS 端口
            props.put("mail.smtp.starttls.enable", "true");
        }
        props.put("mail.smtp.connectiontimeout", TIMEOUT_MS);
        props.put("mail.smtp.timeout", TIMEOUT_MS);
        props.put("mail.smtp.writetimeout", TIMEOUT_MS);
        return sender;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
