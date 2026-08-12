// agent-platform/backend/src/main/java/com/superprogrammer/auth/config/AliyunMailConfig.java
package com.superprogrammer.auth.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 阿里云邮件推送（DirectMail）配置。
 *
 * <p>密钥走环境变量（零入库红线，见 AGENTS.md）：
 * {@code ALIYUN_DM_AK} / {@code ALIYUN_DM_SK} 由启动脚本注入，仓库只存 application.yml 占位。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "aliyun.direct-mail")
public class AliyunMailConfig {

    /** 是否启用邮件推送（开关：出问题能关而不发版，见 plan.md §七运维考量）。 */
    private boolean enabled = false;

    /** AccessKey ID（环境变量 ALIYUN_DM_AK）。 */
    private String accessKeyId;

    /** AccessKey Secret（环境变量 ALIYUN_DM_SK）。 */
    private String accessKeySecret;

    /** 发信地址（如 noreply@yourdomain.com）。 */
    private String accountName;

    /** 区域（如 cn-hangzhou）。 */
    private String region = "cn-hangzhou";

    /** 发信类型（0=批量，1=触发；本功能用触发=1）。 */
    private int addressType = 1;

    /** 回信地址（可空，默认同 accountName）。 */
    private String replyToAddress;

    /** 发件人昵称（如"多Agent智能体平台"）。 */
    private String fromAlias = "多Agent智能体平台";
}
