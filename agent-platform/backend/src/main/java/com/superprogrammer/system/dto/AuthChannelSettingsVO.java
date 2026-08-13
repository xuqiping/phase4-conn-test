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
