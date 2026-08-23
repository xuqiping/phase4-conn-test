package com.superprogrammer.system.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class AuthChannelSettingsUpdateRequest {
    @Valid private Mail mail;
    @Valid private Sms sms;

    @Data
    public static class Mail {
        private Boolean enabled;
        @Pattern(regexp = "^[a-z]{2}-[a-z]+-\\d+$", message = "邮件区域格式不正确") private String region;
        private String accessKeyId;
        private String accessKeySecret;
        private String accountName;
        private String fromAlias;
        private String replyToAddress;
        @Pattern(regexp = "^$|^https?://.+", message = "验证链接必须是 HTTP/HTTPS URL") private String verifyUrl;
        @Pattern(regexp = "^$|^https?://.+", message = "重置链接必须是 HTTP/HTTPS URL") private String resetUrl;
        /** 通道类型（12x）：留空不修改 */
        @Pattern(regexp = "^$|^(ALIYUN|SMTP)$", message = "通道类型只能是 ALIYUN 或 SMTP") private String provider;
        private String smtpHost;
        @Min(1) @Max(65535) private Integer smtpPort;
        private Boolean smtpSsl;
        private String smtpUsername;
        /** SMTP 密码/授权码：null=不修改，空白=清除，其他=更新（AES 落库） */
        private String smtpPassword;
        private String smtpFromAlias;
        /** 邮箱验证总开关（12x）：null=不修改 */
        private Boolean verificationRequired;
    }

    @Data
    public static class Sms {
        private Boolean enabled;
        @Pattern(regexp = "^[a-z]{2}-[a-z]+-\\d+$", message = "短信区域格式不正确") private String region;
        private String accessKeyId;
        private String accessKeySecret;
        private String signName;
        @Pattern(regexp = "^$|^SMS_[A-Za-z0-9]+$", message = "验证码模板格式不正确") private String templateCodeVerify;
        @Pattern(regexp = "^$|^SMS_[A-Za-z0-9]+$", message = "重置模板格式不正确") private String templateCodeReset;
        @Min(1) @Max(60) private Integer codeTtlMinutes;
        @Min(1) @Max(1000) private Integer limitPerPhonePerDay;
        @Min(1) @Max(10000) private Integer limitPerIpPerDay;
    }
}
