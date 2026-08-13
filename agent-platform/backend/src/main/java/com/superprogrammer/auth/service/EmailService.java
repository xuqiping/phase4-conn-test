// agent-platform/backend/src/main/java/com/superprogrammer/auth/service/EmailService.java
package com.superprogrammer.auth.service;

import com.aliyuncs.DefaultAcsClient;
import com.aliyuncs.IAcsClient;
import com.aliyuncs.dm.model.v20151123.SingleSendMailRequest;
import com.aliyuncs.dm.model.v20151123.SingleSendMailResponse;
import com.aliyuncs.profile.DefaultProfile;
import com.aliyuncs.profile.IClientProfile;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/**
 * 邮件服务（通道 A：邮箱验证注册 + 通道 D 找回密码发邮件）。
 *
 * <p>职责：发验证邮件/发重置邮件/激活 token 生成校验/重发限流。
 *
 * <p>安全语义：token 用 SecureRandom 32 字节 + Base64URL；用完即删；统一话术防枚举；阿里云超时降级。
 */
@Slf4j
@Service
public class EmailService {

    private final AuthChannelSettingService channelSettings;
    private final StringRedisTemplate redisTemplate;
    private final CredentialService credentialService;

    private static final String VERIFY_TOKEN_PREFIX = "verify:email:";
    public static final String RESET_TOKEN_PREFIX = "reset:pwd:";
    private static final String RESEND_LIMIT_PREFIX = "resend:email:";
    private static final long VERIFY_TOKEN_TTL_SECONDS = 24 * 3600;
    public static final long RESET_TOKEN_TTL_SECONDS = 30 * 60;
    private static final long RESEND_WINDOW_SECONDS = 60;

    private final SecureRandom secureRandom = new SecureRandom();

    public EmailService(AuthChannelSettingService channelSettings, StringRedisTemplate redisTemplate,
                        CredentialService credentialService) {
        this.channelSettings = channelSettings;
        this.redisTemplate = redisTemplate;
        this.credentialService = credentialService;
    }

    /** 发验证邮件（注册时调用）。 */
    public boolean sendVerifyEmail(Long userId, String email) {
        var config = channelSettings.mailSnapshot();
        if (!config.enabled() || config.accessKeyId() == null || config.accessKeyId().isBlank()) {
            log.warn("邮件推送未开启或未配置，跳过发验证邮件 userId={}", userId);
            return false;
        }

        String token = generateToken();
        try {
            redisTemplate.opsForValue().set(VERIFY_TOKEN_PREFIX + token, String.valueOf(userId),
                    VERIFY_TOKEN_TTL_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("验证 token 存 Redis 失败 userId={} : {}", userId, e.toString());
            return false;
        }

        String verifyLink = config.verifyUrl() + "?token=" + token;
        String subject = "【多Agent智能体平台】请验证您的邮箱";
        String htmlBody = buildVerifyEmailHtml(verifyLink);

        return sendMail(config, email, subject, htmlBody);
    }

    /** 发重置密码邮件（通道 D 用）。 */
    public boolean sendResetEmail(Long userId, String email) {
        var config = channelSettings.mailSnapshot();
        if (!config.enabled() || config.accessKeyId() == null || config.accessKeyId().isBlank()) {
            log.warn("邮件推送未开启，跳过发重置邮件 userId={}", userId);
            return false;
        }

        String token = generateToken();
        try {
            redisTemplate.opsForValue().set(RESET_TOKEN_PREFIX + token, String.valueOf(userId),
                    RESET_TOKEN_TTL_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("重置 token 存 Redis 失败 userId={} : {}", userId, e.toString());
            return false;
        }

        String resetLink = config.resetUrl() + "?token=" + token;
        String subject = "【多Agent智能体平台】重置密码";
        String htmlBody = buildResetEmailHtml(resetLink);

        return sendMail(config, email, subject, htmlBody);
    }

    /** 校验验证邮件 token（激活链接落地页调）。 */
    public void verifyEmail(String token) {
        if (token == null || token.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "链接无效");
        }

        String userIdStr;
        try {
            userIdStr = redisTemplate.opsForValue().get(VERIFY_TOKEN_PREFIX + token);
        } catch (Exception e) {
            log.error("验证 token 查 Redis 失败 : {}", e.toString());
            throw new BusinessException(ErrorCode.BAD_REQUEST, "链接无效或已过期");
        }

        if (userIdStr == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "链接无效或已过期");
        }

        Long userId = Long.parseLong(userIdStr);
        boolean marked = credentialService.markVerifiedByIdentifier(com.superprogrammer.auth.entity.UserCredential.TYPE_EMAIL,
                findEmailByUserId(userId));
        if (!marked) {
            log.warn("验证邮件时未找到 EMAIL 凭证 userId={}", userId);
        }

        try {
            redisTemplate.delete(VERIFY_TOKEN_PREFIX + token);
        } catch (Exception e) {
            log.warn("验证 token 删除失败(已吞) : {}", e.toString());
        }
    }

    /** 重发验证邮件（限流：同邮箱 60s）。统一话术防枚举。 */
    public String resendVerifyEmail(String email) {
        if (email == null || email.isBlank()) {
            return "若该邮箱已注册且未验证，验证邮件已发送";
        }

        String limitKey = RESEND_LIMIT_PREFIX + email.toLowerCase();
        try {
            Long n = redisTemplate.opsForValue().increment(limitKey);
            if (n != null && n == 1L) {
                redisTemplate.expire(limitKey, RESEND_WINDOW_SECONDS, TimeUnit.SECONDS);
            }
            if (n != null && n > 1) {
                return "发送过于频繁，请 60 秒后再试";
            }
        } catch (Exception e) {
            log.warn("重发限流 Redis 失败(降级放行) : {}", e.toString());
        }

        Long userId = findUserIdByEmail(email);
        if (userId == null) {
            try {
                Thread.sleep(200 + secureRandom.nextInt(300));
            } catch (InterruptedException ignored) {
            }
            return "若该邮箱已注册且未验证，验证邮件已发送";
        }

        boolean alreadyVerified = credentialService.markVerifiedByIdentifier(
                com.superprogrammer.auth.entity.UserCredential.TYPE_EMAIL, email) == false;
        if (!alreadyVerified) {
            return "若该邮箱已注册且未验证，验证邮件已发送";
        }

        boolean sent = sendVerifyEmail(userId, email);
        return sent ? "若该邮箱已注册且未验证，验证邮件已发送" : "发送失败，请稍后重试";
    }

    private String generateToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private boolean sendMail(AuthChannelSettingService.MailSnapshot config,
                             String toEmail, String subject, String htmlBody) {
        try {
            IClientProfile profile = DefaultProfile.getProfile(config.region(),
                    config.accessKeyId(), config.accessKeySecret());
            IAcsClient client = new DefaultAcsClient(profile);

            SingleSendMailRequest request = new SingleSendMailRequest();
            request.setAccountName(config.accountName());
            request.setAddressType(1);
            request.setReplyToAddress(config.replyToAddress() != null && !config.replyToAddress().isBlank());
            request.setToAddress(toEmail);
            request.setSubject(subject);
            request.setHtmlBody(htmlBody);
            request.setFromAlias(config.fromAlias());

            SingleSendMailResponse response = client.getAcsResponse(request);
            log.info("邮件发送成功 to={} subject={} requestId={}", maskEmail(toEmail), subject, response.getRequestId());
            return true;
        } catch (Exception e) {
            log.error("邮件发送失败 to={} subject={} : {}", maskEmail(toEmail), subject, e.toString());
            return false;
        }
    }

    private Long findUserIdByEmail(String email) {
        var credential = credentialService.findForLogin(com.superprogrammer.auth.entity.UserCredential.TYPE_EMAIL, email);
        return credential == null ? null : credential.getUserId();
    }

    private String findEmailByUserId(Long userId) {
        var credentials = credentialService.findByUserIdRaw(userId);
        return credentials.stream()
                .filter(c -> com.superprogrammer.auth.entity.UserCredential.TYPE_EMAIL.equals(c.getCredentialType()))
                .map(com.superprogrammer.auth.entity.UserCredential::getIdentifier)
                .findFirst()
                .orElse(null);
    }

    private String maskEmail(String email) {
        if (email == null || email.isBlank()) return "";
        int at = email.indexOf('@');
        if (at <= 1) return email.charAt(0) + "***" + (at > 0 ? email.substring(at) : "");
        return email.charAt(0) + "***" + email.substring(at);
    }

    private String buildVerifyEmailHtml(String verifyLink) {
        return "<div style='font-family:sans-serif;max-width:600px;margin:0 auto;padding:20px;'>"
                + "<h2 style='color:#333;'>验证您的邮箱</h2>"
                + "<p>感谢您注册多Agent智能体平台。请点击下方链接验证您的邮箱：</p>"
                + "<p><a href='" + verifyLink + "' style='display:inline-block;padding:12px 24px;background:#4A90D9;color:#fff;text-decoration:none;border-radius:4px;'>验证邮箱</a></p>"
                + "<p style='color:#999;font-size:12px;'>链接 24 小时内有效。若非本人操作，请忽略本邮件。</p>"
                + "</div>";
    }

    private String buildResetEmailHtml(String resetLink) {
        return "<div style='font-family:sans-serif;max-width:600px;margin:0 auto;padding:20px;'>"
                + "<h2 style='color:#333;'>重置密码</h2>"
                + "<p>您正在重置多Agent智能体平台账号的密码。请点击下方链接设置新密码：</p>"
                + "<p><a href='" + resetLink + "' style='display:inline-block;padding:12px 24px;background:#E74C3C;color:#fff;text-decoration:none;border-radius:4px;'>重置密码</a></p>"
                + "<p style='color:#999;font-size:12px;'>链接 30 分钟内有效。若非本人操作，请忽略本邮件并立即修改密码。</p>"
                + "</div>";
    }
}
