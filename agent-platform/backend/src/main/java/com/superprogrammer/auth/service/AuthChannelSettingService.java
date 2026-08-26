package com.superprogrammer.auth.service;

import com.superprogrammer.auth.config.AliyunMailConfig;
import com.superprogrammer.auth.config.AliyunSmsConfig;
import com.superprogrammer.system.dto.AuthChannelSettingsVO;
import com.superprogrammer.system.dto.AuthChannelSettingsUpdateRequest;
import com.superprogrammer.system.service.SystemSettingService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** 认证通道运行时配置解析：数据库配置优先，部署环境配置兜底。 */
@Service
@RequiredArgsConstructor
public class AuthChannelSettingService {
    private static final String MAIL = "auth.channel.mail.";
    private static final String SMS = "auth.channel.sms.";
    /** 12x 开关回退：邮箱验证总开关（注册强制邮箱验证码 + 充值需已验证邮箱）。
     *  默认关——真实邮箱通道（腾讯 SMTP 等）接入并测通后再开。 */
    public static final String KEY_MAIL_VERIFICATION_REQUIRED = MAIL + "verification-required";

    private final SystemSettingService settings;
    private final AliyunMailConfig mailFallback;
    private final AliyunSmsConfig smsFallback;

    @Value("${app.auth.email.enabled:false}") private boolean emailEnabledFallback;
    @Value("${app.auth.email.verify-url:}") private String verifyUrlFallback;
    @Value("${app.auth.email.reset-url:}") private String resetUrlFallback;
    @Value("${app.auth.sms.enabled:false}") private boolean smsEnabledFallback;
    @Value("${app.auth.sms.code-ttl-minutes:5}") private int smsCodeTtlFallback = 5;
    @Value("${app.auth.sms.limit-per-phone-per-day:10}") private int smsPhoneLimitFallback = 10;
    @Value("${app.auth.sms.limit-per-ip-per-day:30}") private int smsIpLimitFallback = 30;
    // 12x SMTP 通道 env 兜底（网页配置优先）
    @Value("${app.auth.email.provider:ALIYUN}") private String mailProviderFallback;
    @Value("${app.auth.email.smtp.host:}") private String smtpHostFallback;
    @Value("${app.auth.email.smtp.port:465}") private int smtpPortFallback;
    @Value("${app.auth.email.smtp.ssl:true}") private boolean smtpSslFallback;
    @Value("${app.auth.email.smtp.username:}") private String smtpUsernameFallback;
    @Value("${app.auth.email.smtp.password:}") private String smtpPasswordFallback;
    @Value("${app.auth.email.smtp.from-alias:}") private String smtpFromAliasFallback;

    public MailSnapshot mailSnapshot() {
        return new MailSnapshot(
                bool(MAIL + "enabled", emailEnabledFallback),
                text(MAIL + "region", mailFallback.getRegion()),
                text(MAIL + "access-key-id", mailFallback.getAccessKeyId()),
                secret(MAIL + "access-key-secret", mailFallback.getAccessKeySecret()),
                text(MAIL + "account-name", mailFallback.getAccountName()),
                text(MAIL + "from-alias", mailFallback.getFromAlias()),
                text(MAIL + "reply-to-address", mailFallback.getReplyToAddress()),
                text(MAIL + "verify-url", verifyUrlFallback),
                text(MAIL + "reset-url", resetUrlFallback),
                text(MAIL + "provider", mailProviderFallback),
                new Smtp(
                        text(MAIL + "smtp-host", smtpHostFallback),
                        positiveInt(MAIL + "smtp-port", smtpPortFallback),
                        bool(MAIL + "smtp-ssl", smtpSslFallback),
                        text(MAIL + "smtp-username", smtpUsernameFallback),
                        secret(MAIL + "smtp-password", smtpPasswordFallback),
                        text(MAIL + "smtp-from-alias", smtpFromAliasFallback)));
    }

    /**
     * 12x-1 C2：注册发码间隔秒数（system_settings 键 auth.channel.mail.resend-interval-seconds，默认 60）。
     * 每请求实时读（与 daily-cap 同款）——改配置即时生效，无需重启。
     */
    public long resendIntervalSeconds() {
        try {
            long v = settings.getLong(MAIL + "resend-interval-seconds", 60L);
            return v > 0 ? v : 60L;
        } catch (Exception e) {
            return 60L;
        }
    }

    public SmsSnapshot smsSnapshot() {
        return new SmsSnapshot(
                bool(SMS + "enabled", smsEnabledFallback),
                text(SMS + "region", smsFallback.getRegion()),
                text(SMS + "access-key-id", smsFallback.getAccessKeyId()),
                secret(SMS + "access-key-secret", smsFallback.getAccessKeySecret()),
                text(SMS + "sign-name", smsFallback.getSignName()),
                text(SMS + "template-code-verify", smsFallback.getTemplateCodeVerify()),
                text(SMS + "template-code-reset", smsFallback.getTemplateCodeReset()),
                positiveInt(SMS + "code-ttl-minutes", smsCodeTtlFallback),
                positiveInt(SMS + "limit-per-phone-per-day", smsPhoneLimitFallback),
                positiveInt(SMS + "limit-per-ip-per-day", smsIpLimitFallback));
    }

    /** 邮箱验证总开关（12x）：开=注册强制邮箱验证码 + 充值需已验证邮箱；关=邮箱可选填不验码。 */
    public boolean isEmailVerificationRequired() {
        return bool(KEY_MAIL_VERIFICATION_REQUIRED, false);
    }

    public AuthChannelSettingsVO getSettings() {
        MailSnapshot mail = mailSnapshot();
        SmsSnapshot sms = smsSnapshot();
        return AuthChannelSettingsVO.builder()
                .mail(AuthChannelSettingsVO.Mail.builder()
                        .enabled(mail.enabled()).region(mail.region()).accessKeyId(mail.accessKeyId())
                        .secretConfigured(hasText(mail.accessKeySecret())).accountName(mail.accountName())
                        .fromAlias(mail.fromAlias()).replyToAddress(mail.replyToAddress())
                        .verifyUrl(mail.verifyUrl()).resetUrl(mail.resetUrl())
                        .provider(mail.provider())
                        .smtpHost(mail.smtp().host()).smtpPort(mail.smtp().port()).smtpSsl(mail.smtp().ssl())
                        .smtpUsername(mail.smtp().username())
                        .smtpPasswordConfigured(hasText(mail.smtp().password()))
                        .smtpFromAlias(mail.smtp().fromAlias())
                        .verificationRequired(isEmailVerificationRequired())
                        .build())
                .sms(AuthChannelSettingsVO.Sms.builder()
                        .enabled(sms.enabled()).region(sms.region()).accessKeyId(sms.accessKeyId())
                        .secretConfigured(hasText(sms.accessKeySecret())).signName(sms.signName())
                        .templateCodeVerify(sms.templateCodeVerify()).templateCodeReset(sms.templateCodeReset())
                        .codeTtlMinutes(sms.codeTtlMinutes()).limitPerPhonePerDay(sms.limitPerPhonePerDay())
                        .limitPerIpPerDay(sms.limitPerIpPerDay()).build())
                .build();
    }

    public AuthChannelSettingsVO update(AuthChannelSettingsUpdateRequest request) {
        if (request.getMail() != null) updateMail(request.getMail());
        if (request.getSms() != null) updateSms(request.getSms());
        return getSettings();
    }

    private void updateMail(AuthChannelSettingsUpdateRequest.Mail v) {
        plain(MAIL + "enabled", v.getEnabled(), "邮件通道启用开关");
        plain(MAIL + "region", v.getRegion(), "邮件区域");
        plain(MAIL + "access-key-id", v.getAccessKeyId(), "邮件 AccessKey ID");
        secretUpdate(MAIL + "access-key-secret", v.getAccessKeySecret(), "邮件 AccessKey Secret（AES 加密）");
        plain(MAIL + "account-name", v.getAccountName(), "邮件发信地址");
        plain(MAIL + "from-alias", v.getFromAlias(), "邮件发件人昵称");
        plain(MAIL + "reply-to-address", v.getReplyToAddress(), "邮件回信地址");
        plain(MAIL + "verify-url", v.getVerifyUrl(), "邮箱验证链接前缀");
        plain(MAIL + "reset-url", v.getResetUrl(), "密码重置链接前缀");
        plain(MAIL + "provider", v.getProvider(), "邮件通道类型（ALIYUN/SMTP）");
        plain(MAIL + "smtp-host", v.getSmtpHost(), "SMTP 服务器");
        plain(MAIL + "smtp-port", v.getSmtpPort(), "SMTP 端口");
        plain(MAIL + "smtp-ssl", v.getSmtpSsl(), "SMTP SSL 直连");
        plain(MAIL + "smtp-username", v.getSmtpUsername(), "SMTP 用户名（邮箱地址）");
        secretUpdate(MAIL + "smtp-password", v.getSmtpPassword(), "SMTP 密码/授权码（AES 加密）");
        plain(MAIL + "smtp-from-alias", v.getSmtpFromAlias(), "SMTP 发件人昵称");
        plain(KEY_MAIL_VERIFICATION_REQUIRED, v.getVerificationRequired(),
                "邮箱验证总开关（12x）：开=注册强制邮箱验证码+充值需已验证邮箱");
    }

    private void updateSms(AuthChannelSettingsUpdateRequest.Sms v) {
        plain(SMS + "enabled", v.getEnabled(), "短信通道启用开关");
        plain(SMS + "region", v.getRegion(), "短信区域");
        plain(SMS + "access-key-id", v.getAccessKeyId(), "短信 AccessKey ID");
        secretUpdate(SMS + "access-key-secret", v.getAccessKeySecret(), "短信 AccessKey Secret（AES 加密）");
        plain(SMS + "sign-name", v.getSignName(), "短信签名");
        plain(SMS + "template-code-verify", v.getTemplateCodeVerify(), "短信验证码模板");
        plain(SMS + "template-code-reset", v.getTemplateCodeReset(), "短信重置密码模板");
        plain(SMS + "code-ttl-minutes", v.getCodeTtlMinutes(), "短信验证码有效分钟数");
        plain(SMS + "limit-per-phone-per-day", v.getLimitPerPhonePerDay(), "每手机号每日短信上限");
        plain(SMS + "limit-per-ip-per-day", v.getLimitPerIpPerDay(), "每 IP 每日短信上限");
    }

    private void plain(String key, Object value, String description) {
        if (value != null) settings.upsertSettingValue(key, String.valueOf(value).trim(), description);
    }

    private void secretUpdate(String key, String value, String description) {
        if (value == null) return;
        if (value.isBlank()) settings.clearSettingValue(key);
        else settings.upsertEncrypted(key, value, description);
    }

    private String text(String key, String fallback) {
        String value = settings.getSettingValue(key);
        return hasText(value) ? value.trim() : fallback;
    }

    private String secret(String key, String fallback) {
        String value = settings.getDecryptedValue(key);
        return hasText(value) ? value : fallback;
    }

    private boolean bool(String key, boolean fallback) {
        String value = settings.getSettingValue(key);
        return hasText(value) ? Boolean.parseBoolean(value) : fallback;
    }

    private int positiveInt(String key, int fallback) {
        String value = settings.getSettingValue(key);
        if (!hasText(value)) return fallback;
        try {
            int parsed = Integer.parseInt(value);
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private boolean hasText(String value) { return value != null && !value.isBlank(); }

    public record MailSnapshot(boolean enabled, String region, String accessKeyId, String accessKeySecret,
                               String accountName, String fromAlias, String replyToAddress,
                               String verifyUrl, String resetUrl,
                               String provider, Smtp smtp) {}

    /** SMTP 通道参数（12x：腾讯/网易等任意邮箱；password=授权码/客户端专用密码，AES 落库）。 */
    public record Smtp(String host, int port, boolean ssl, String username, String password, String fromAlias) {}

    public record SmsSnapshot(boolean enabled, String region, String accessKeyId, String accessKeySecret,
                              String signName, String templateCodeVerify, String templateCodeReset,
                              int codeTtlMinutes, int limitPerPhonePerDay, int limitPerIpPerDay) {}
}
