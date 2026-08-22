// agent-platform/backend/src/main/java/com/superprogrammer/auth/service/EmailService.java
package com.superprogrammer.auth.service;

import com.superprogrammer.auth.service.mail.MailSendQuotaService;
import com.superprogrammer.auth.service.mail.MailSender;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 邮件服务（通道 A：邮箱验证注册 + 通道 D 找回密码发邮件）。
 *
 * <p>职责：发验证邮件/发重置邮件/激活 token 生成校验/重发限流。
 *
 * <p>安全语义：token 用 SecureRandom 32 字节 + Base64URL；用完即删；统一话术防枚举；通道超时降级。
 *
 * <p>12x 运输层抽象：实际发信委托 {@link MailSender}（ALIYUN / SMTP），按配置 provider 路由。</p>
 */
@Slf4j
@Service
public class EmailService {

    private final AuthChannelSettingService channelSettings;
    private final StringRedisTemplate redisTemplate;
    private final CredentialService credentialService;
    private final MailSendQuotaService mailQuota;
    private final ProgressiveCaptchaGuard captchaGuard;
    private final Map<String, MailSender> senders;

    private static final String VERIFY_TOKEN_PREFIX = "verify:email:";
    public static final String RESET_TOKEN_PREFIX = "reset:pwd:";
    private static final String RESEND_LIMIT_PREFIX = "resend:email:";
    private static final long VERIFY_TOKEN_TTL_SECONDS = 24 * 3600;
    public static final long RESET_TOKEN_TTL_SECONDS = 30 * 60;
    private static final long RESEND_WINDOW_SECONDS = 60;

    // 12x B1：注册邮箱 6 位数字码（取代注册后链接激活——注册即证明邮箱归属，EMAIL 凭证直接 verified=TRUE）。
    // 码 10min TTL；同码错 5 次锁（防 6 位码暴破）；同邮箱 60s 重发 + IP 10 封/h（复用 B3 配额）。
    private static final String REG_CODE_PREFIX = "regcode:email:";
    private static final String REG_TRY_PREFIX = "regcode:try:";
    private static final String REG_RESEND_PREFIX = "regcode:resend:";
    private static final long REG_CODE_TTL_SECONDS = 10 * 60;
    private static final long REG_CODE_MAX_TRIES = 5;

    private final SecureRandom secureRandom = new SecureRandom();

    public EmailService(AuthChannelSettingService channelSettings, StringRedisTemplate redisTemplate,
                        CredentialService credentialService, MailSendQuotaService mailQuota,
                        ProgressiveCaptchaGuard captchaGuard, List<MailSender> mailSenders) {
        this.channelSettings = channelSettings;
        this.redisTemplate = redisTemplate;
        this.credentialService = credentialService;
        this.mailQuota = mailQuota;
        this.captchaGuard = captchaGuard;
        this.senders = mailSenders.stream().collect(Collectors.toMap(MailSender::provider, Function.identity()));
    }

    /** 发验证邮件（注册时调用）。 */
    public boolean sendVerifyEmail(Long userId, String email) {
        var config = channelSettings.mailSnapshot();
        if (!config.enabled() || !isConfigured(config)) {
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
        if (!config.enabled() || !isConfigured(config)) {
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

    /** 重发验证邮件（限流：同邮箱 60s + 同 IP 10 封/h〔12x B3〕）。统一话术防枚举。 */
    public String resendVerifyEmail(String email, String clientIp) {
        if (email == null || email.isBlank()) {
            return "若该邮箱已注册且未验证，验证邮件已发送";
        }

        // 12x B3：IP 维度补闸（原只有邮箱维度，轰炸机换邮箱即绕）
        mailQuota.checkIpHourly(clientIp);

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

    /**
     * 测试发信（认证通道页「发送测试邮件」按钮，12x）。
     * <p>不要求 enabled=true——先测通再开开关；但要求所选通道配置完整。
     * 不看 provider 的 enabled 语义，直接按当前快照路由发送。</p>
     */
    public boolean sendTestMail(String toEmail) {
        var config = channelSettings.mailSnapshot();
        if (!isConfigured(config)) {
            log.warn("测试发信：通道未配置完整 provider={}", config.provider());
            return false;
        }
        String subject = "【多Agent智能体平台】邮件通道测试";
        String htmlBody = "<div style='font-family:sans-serif;max-width:600px;margin:0 auto;padding:20px;'>"
                + "<h2 style='color:#333;'>邮件通道配置成功</h2>"
                + "<p>这是一封测试邮件。收到它说明当前邮件通道（" + config.provider() + "）配置正确，验证邮件与找回密码邮件将由此发出。</p>"
                + "</div>";
        return sendMail(config, toEmail, subject, htmlBody);
    }

    /**
     * B4：密码重置成功告警信（发给被重置账号的已验证邮箱）。
     * <p>防盗号链「邮箱失陷→重置→换密→卷积分」无感知——受害者收信可及时申诉。
     * 发送失败只记日志，不回滚重置主流程。</p>
     */
    public void sendPasswordResetAlertEmail(String email, String resetTimeText) {
        var config = channelSettings.mailSnapshot();
        if (!config.enabled() || !isConfigured(config)) {
            log.warn("邮件推送未开启，跳过重置告警信");
            return;
        }
        String subject = "【多Agent智能体平台】您的账号密码已重置";
        String htmlBody = "<div style='font-family:sans-serif;max-width:600px;margin:0 auto;padding:20px;'>"
                + "<h2 style='color:#E74C3C;'>密码已重置</h2>"
                + "<p>您的账号密码已于 <b>" + resetTimeText + "</b> 通过「找回密码」完成重置，全部登录会话已强制退出。</p>"
                + "<p style='color:#999;font-size:12px;'>若这是您本人操作，请忽略本邮件。若非本人操作，您的邮箱可能已泄露，请立即修改邮箱密码并联系平台管理员冻结账号。</p>"
                + "</div>";
        sendMail(config, email, subject, htmlBody);
    }

    // ==================== 12x B1：注册邮箱验证码 ====================

    /**
     * 发注册验证码（公开端点，注册前置）。
     * <p>强校验语义：邮件通道未开启/未配置完整 → 直接拒绝（注册必须验邮箱，通道坏了=注册暂停，宁缺毋滥）。
     * 邮箱已被注册 → CONFLICT（不防枚举——发码行为本身要求证明邮箱归属，泄露成本可接受且话术指引找回）。
     * 12x B2：同 IP 连续失败 ≥2 次 → 强制滑块。</p>
     */
    public void sendRegisterCode(String email, String clientIp, String captchaVerification) {
        captchaGuard.check("mailcode", clientIp, captchaVerification);
        try {
            doSendRegisterCode(email, clientIp);
        } catch (BusinessException e) {
            captchaGuard.recordFailure("mailcode", clientIp);
            throw e;
        }
        captchaGuard.clear("mailcode", clientIp);
    }

    private void doSendRegisterCode(String email, String clientIp) {
        String normalized = email == null ? "" : email.trim().toLowerCase();
        var config = channelSettings.mailSnapshot();
        if (!config.enabled() || !isConfigured(config)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "邮件通道未开启，暂无法注册，请联系管理员");
        }
        if (findUserIdByEmail(normalized) != null) {
            throw new BusinessException(ErrorCode.CONFLICT, "该邮箱已被注册，请直接登录或使用找回密码");
        }

        mailQuota.checkIpHourly(clientIp);

        String resendKey = REG_RESEND_PREFIX + normalized;
        try {
            Long n = redisTemplate.opsForValue().increment(resendKey);
            if (n != null && n == 1L) {
                redisTemplate.expire(resendKey, RESEND_WINDOW_SECONDS, TimeUnit.SECONDS);
            }
            if (n != null && n > 1) {
                throw new BusinessException(ErrorCode.RATE_LIMIT, "发送过于频繁，请 60 秒后再试");
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("注册发码限流 Redis 失败(降级放行) : {}", e.toString());
        }

        String code = String.format("%06d", secureRandom.nextInt(1_000_000));
        try {
            redisTemplate.opsForValue().set(REG_CODE_PREFIX + normalized, code, REG_CODE_TTL_SECONDS, TimeUnit.SECONDS);
            redisTemplate.opsForValue().set(REG_TRY_PREFIX + normalized, "0", REG_CODE_TTL_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("注册验证码存 Redis 失败 : {}", e.toString());
            throw new BusinessException(ErrorCode.BAD_REQUEST, "验证码发送失败，请稍后重试");
        }

        String subject = "【多Agent智能体平台】注册验证码";
        String htmlBody = "<div style='font-family:sans-serif;max-width:600px;margin:0 auto;padding:20px;'>"
                + "<h2 style='color:#333;'>注册验证码</h2>"
                + "<p>您正在注册多Agent智能体平台账号，验证码为：</p>"
                + "<p style='font-size:32px;font-weight:bold;letter-spacing:6px;color:#4A90D9;'>" + code + "</p>"
                + "<p style='color:#999;font-size:12px;'>验证码 10 分钟内有效，输错 5 次作废。若非本人操作，请忽略本邮件。</p>"
                + "</div>";
        if (!sendMail(config, normalized, subject, htmlBody)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "验证码发送失败，请稍后重试");
        }
    }

    /**
     * 校验注册验证码（AuthService.register 建号前调）。
     * <p>成功即删码（一次性）；错 5 次作废须重新获取。Redis 故障 → fail closed（注册必须证明邮箱归属，不降级放行）。</p>
     */
    public void verifyRegisterCode(String email, String code) {
        String normalized = email == null ? "" : email.trim().toLowerCase();
        String codeKey = REG_CODE_PREFIX + normalized;
        String stored;
        try {
            stored = redisTemplate.opsForValue().get(codeKey);
        } catch (Exception e) {
            log.error("注册验证码查 Redis 失败(fail closed) : {}", e.toString());
            throw new BusinessException(ErrorCode.BAD_REQUEST, "验证码校验服务异常，请稍后重试");
        }
        if (stored == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "验证码已过期，请重新获取");
        }

        String tryKey = REG_TRY_PREFIX + normalized;
        try {
            Long tries = redisTemplate.opsForValue().increment(tryKey);
            if (tries != null && tries > REG_CODE_MAX_TRIES) {
                redisTemplate.delete(codeKey);
                redisTemplate.delete(tryKey);
                throw new BusinessException(ErrorCode.BAD_REQUEST, "验证码错误次数过多，请重新获取");
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("注册验证码试错计数 Redis 失败(fail closed) : {}", e.toString());
            throw new BusinessException(ErrorCode.BAD_REQUEST, "验证码校验服务异常，请稍后重试");
        }

        if (!stored.equals(code == null ? "" : code.trim())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "验证码错误");
        }
        redisTemplate.delete(codeKey);
        redisTemplate.delete(tryKey);
    }

    /** 通道是否配置完整（按 provider 分流校验）。 */
    private boolean isConfigured(AuthChannelSettingService.MailSnapshot config) {
        if ("SMTP".equalsIgnoreCase(config.provider())) {
            var smtp = config.smtp();
            return smtp != null && hasText(smtp.host()) && hasText(smtp.username()) && hasText(smtp.password());
        }
        return hasText(config.accessKeyId());
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private String generateToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** 按 provider 路由到具体通道发信（12x 抽象）；B3：全局日总量封顶防超额封号。 */
    private boolean sendMail(AuthChannelSettingService.MailSnapshot config,
                             String toEmail, String subject, String htmlBody) {
        String provider = hasText(config.provider()) ? config.provider().toUpperCase() : "ALIYUN";
        MailSender sender = senders.get(provider);
        if (sender == null) {
            log.error("未知邮件通道 provider={}，可用={}", provider, senders.keySet());
            return false;
        }
        if (!mailQuota.tryConsumeDaily()) {
            return false;
        }
        return sender.send(config, toEmail, subject, htmlBody);
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
