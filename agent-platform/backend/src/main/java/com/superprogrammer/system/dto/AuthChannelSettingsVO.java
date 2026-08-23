package com.superprogrammer.system.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AuthChannelSettingsVO {
    private Mail mail;
    private Sms sms;

    @Data
    @Builder
    public static class Mail {
        private Boolean enabled;
        private String region;
        private String accessKeyId;
        private Boolean secretConfigured;
        private String accountName;
        private String fromAlias;
        private String replyToAddress;
        private String verifyUrl;
        private String resetUrl;
        /** 通道类型：ALIYUN（阿里云 DM）/ SMTP（通用邮箱，12x） */
        private String provider;
        private String smtpHost;
        private Integer smtpPort;
        private Boolean smtpSsl;
        private String smtpUsername;
        private Boolean smtpPasswordConfigured;
        private String smtpFromAlias;
        /** 邮箱验证总开关（12x）：开=注册强制邮箱验证码 + 充值需已验证邮箱；默认关 */
        private Boolean verificationRequired;
    }

    @Data
    @Builder
    public static class Sms {
        private Boolean enabled;
        private String region;
        private String accessKeyId;
        private Boolean secretConfigured;
        private String signName;
        private String templateCodeVerify;
        private String templateCodeReset;
        private Integer codeTtlMinutes;
        private Integer limitPerPhonePerDay;
        private Integer limitPerIpPerDay;
    }
}
