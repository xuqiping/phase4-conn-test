// agent-platform/backend/src/main/java/com/superprogrammer/auth/config/AliyunSmsConfig.java
package com.superprogrammer.auth.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 阿里云短信（SMS）配置。
 *
 * <p>密钥走环境变量（零入库红线，见 AGENTS.md）：
 * {@code ALIYUN_SMS_AK} / {@code ALIYUN_SMS_SK} 由启动脚本注入，仓库只存 application.yml 占位。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "aliyun.sms")
public class AliyunSmsConfig {

    /** 是否启用短信（开关：出问题能关而不发版，见 plan.md §七运维考量）。 */
    private boolean enabled = false;

    /** AccessKey ID（环境变量 ALIYUN_SMS_AK）。 */
    private String accessKeyId;

    /** AccessKey Secret（环境变量 ALIYUN_SMS_SK）。 */
    private String accessKeySecret;

    /** 短信签名（如"多Agent平台"）。 */
    private String signName;

    /** 验证码短信模板 ID（如 SMS_XXXXXXXX）。 */
    private String templateCodeVerify;

    /** 重置密码短信模板 ID（如 SMS_YYYYYYYY）。 */
    private String templateCodeReset;

    /** 区域（如 cn-hangzhou）。 */
    private String region = "cn-hangzhou";
}
