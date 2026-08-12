// agent-platform/backend/src/main/java/com/superprogrammer/auth/config/WechatMpConfig.java
package com.superprogrammer.auth.config;

import lombok.Data;
import me.chanjar.weixin.mp.api.WxMpService;
import me.chanjar.weixin.mp.api.impl.WxMpServiceImpl;
import me.chanjar.weixin.mp.config.impl.WxMpDefaultConfigImpl;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 微信开放平台配置（通道 C：微信扫码登录）。
 *
 * <p>{@code @ConditionalOnProperty} 守卫：仅当 {@code app.auth.wechat.enabled=true} 时初始化 WxMpService Bean。
 * 未配置时不创建 Bean（不拖垮启动），Controller 返"微信登录未开启"。
 *
 * <p>密钥走环境变量（零入库红线）：{@code APP_WECHAT_APP_SECRET} 由启动脚本注入。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "app.auth.wechat")
@ConditionalOnProperty(name = "app.auth.wechat.enabled", havingValue = "true")
public class WechatMpConfig {

    /** 是否启用。 */
    private boolean enabled = false;

    /** 微信开放平台 AppID（网站应用）。 */
    private String appId;

    /** 微信开放平台 AppSecret（环境变量 APP_WECHAT_APP_SECRET）。 */
    private String appSecret;

    /** 授权回调地址（后端 callback 端点完整 URL）。 */
    private String redirectUri;

    /**
     * WxMpService Bean（WxJava 核心）。
     * 仅 enabled=true 时创建；其他情况 Controller 直接返回"未开启"。
     */
    @Bean
    public WxMpService wxMpService() {
        WxMpDefaultConfigImpl config = new WxMpDefaultConfigImpl();
        config.setAppId(appId);
        config.setSecret(appSecret);

        WxMpService service = new WxMpServiceImpl();
        service.setWxMpConfigStorage(config);
        return service;
    }
}
