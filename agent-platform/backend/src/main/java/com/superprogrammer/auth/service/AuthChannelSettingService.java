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
                text(MAIL + "reset-url", resetUrlFallback));
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

    public AuthChannelSettingsVO getSettings() {
        MailSnapshot mail = mailSnapshot();
        SmsSnapshot sms = smsSnapshot();
        return AuthChannelSettingsVO.builder()
                .mail(AuthChannelSettingsVO.Mail.builder()
                        .enabled(mail.enabled()).region(mail.region()).accessKeyId(mail.accessKeyId())
                        .secretConfigured(hasText(mail.accessKeySecret())).accountName(mail.accountName())
                        .fromAlias(mail.fromAlias()).replyToAddress(mail.replyToAddress())
                        .verifyUrl(mail.verifyUrl()).resetUrl(mail.resetUrl()).build())
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
                               String verifyUrl, String resetUrl) {}

    public record SmsSnapshot(boolean enabled, String region, String accessKeyId, String accessKeySecret,
                              String signName, String templateCodeVerify, String templateCodeReset,
                              int codeTtlMinutes, int limitPerPhonePerDay, int limitPerIpPerDay) {}
}
