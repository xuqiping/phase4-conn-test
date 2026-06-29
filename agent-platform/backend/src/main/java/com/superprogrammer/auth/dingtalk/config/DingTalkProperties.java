package com.superprogrammer.auth.dingtalk.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "dingtalk")
public class DingTalkProperties {

    /** 是否启用钉钉免登（默认关，配齐密钥再开） */
    private boolean enabled = false;

    /** H5 微应用 AppKey（= OAuth client_id） */
    private String appKey;

    /** H5 微应用 AppSecret */
    private String appSecret;

    /** 微应用 AgentId（记录用，OAuth 免登流程不强制） */
    private String agentId;
}
