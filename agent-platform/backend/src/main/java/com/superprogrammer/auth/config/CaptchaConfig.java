// agent-platform/backend/src/main/java/com/superprogrammer/auth/config/CaptchaConfig.java
package com.superprogrammer.auth.config;

import com.anji.captcha.config.AjCaptchaServiceAutoConfiguration;
import com.anji.captcha.properties.AjCaptchaProperties;
import com.anji.captcha.service.CaptchaService;
import com.anji.captcha.service.impl.CaptchaServiceFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * AJ-Captcha 滑块验证码手动装配（认证系统增强 Chunk C/F，Phase 4 修复重写）。
 *
 * <p><b>为什么手动装配</b>：starter（spring-boot-starter-captcha:1.3.0，2021 年为 Boot 2 构建）
 * 的自动配置通过 META-INF/spring.factories 注册——Spring Boot 3.x 已废弃该机制
 * （需 AutoConfiguration.imports 文件），导致 {@code AjCaptchaServiceAutoConfiguration}
 * 在本项目（Boot 3.2.5）下从不生效、CaptchaService bean 缺失。
 *
 * <p><b>历史坑（Phase 4 实证）</b>：本类初版用 {@code CaptchaServiceFactory.getInstance(null)}
 * 直接 NPE（该工厂签名是 getInstance(Properties)，null 传参炸在 config.getProperty）。
 * 现改为委托官方 {@link AjCaptchaServiceAutoConfiguration#captchaService}——属性映射
 * （captcha.cacheType / captcha.type / captcha.slip.offset …12 项）、
 * initializeBaseMap（预载拼图底图）全部复用官方逻辑，零手工映射。
 *
 * <p><b>Redis 缓存实现</b>：官方 jar 只带 local 内存实现，redis 需业务方自写
 * （{@link CaptchaCacheServiceRedisImpl}）。必须在 getInstance 之前注册进
 * {@code CaptchaServiceFactory.cacheService}（key = type() = "redis"，小写，
 * 与 StorageType 枚举常量名一致）。
 *
 * <p>配置前缀：{@code aj.captcha.*}（application.yml，注意不是 captcha.*——
 * 前缀写错属性不绑定，单测 mock 掩盖、运行时才炸）。
 */
@Configuration
@EnableConfigurationProperties(AjCaptchaProperties.class)
public class CaptchaConfig {

    /**
     * AJ-Captcha 服务 Bean：注册 redis 缓存实现 → 委托官方自动配置类构建
     * （读 aj.captcha.* 配置：cache-type=redis 跨实例共享 + 单次有效防重放）。
     * bean 名用 ajCaptchaService——避免与 auth.service.CaptchaService（项目封装 @Service，
     * bean 名 captchaService）同名冲突（Boot 2.1+ 默认禁 bean 覆盖）。
     * 两者类型不同（本 bean 是库接口 com.anji.captcha.service.CaptchaService，
     * 项目封装类注入的正是这个库类型），按类型注入互不干扰。
     */
    @Bean
    public CaptchaService ajCaptchaService(AjCaptchaProperties properties,
                                           StringRedisTemplate redisTemplate) {
        CaptchaServiceFactory.cacheService.put("redis",
                new CaptchaCacheServiceRedisImpl(redisTemplate));
        return new AjCaptchaServiceAutoConfiguration().captchaService(properties);
    }
}
