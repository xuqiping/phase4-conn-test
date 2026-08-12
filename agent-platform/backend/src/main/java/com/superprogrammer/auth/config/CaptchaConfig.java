// agent-platform/backend/src/main/java/com/superprogrammer/auth/config/CaptchaConfig.java
package com.superprogrammer.auth.config;

import com.anji.captcha.service.CaptchaService;
import com.anji.captcha.service.impl.CaptchaServiceFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AJ-Captcha 滑块验证码配置。
 *
 * <p>用 Redis 缓存（captcha.cache-type=redis），滑块轨迹校验单次有效（通过后删 token）。
 * 参考：https://ajcaptcha.beliefteam.cn/captcha-doc/
 */
@Configuration
public class CaptchaConfig {

    /**
     * AJ-Captcha 服务 Bean（工厂模式创建，按 application.yml captcha.* 配置初始化）。
     */
    @Bean
    public CaptchaService captchaService() {
        return CaptchaServiceFactory.getInstance(null);
    }
}
