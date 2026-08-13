package com.superprogrammer.common.config;

import com.superprogrammer.common.ratelimit.RateLimitInterceptor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置（11x 加固 · P1-C2）：注册通用限流拦截器。
 *
 * <p>拦截器本身只对有 {@code @RateLimit} 注解的方法生效（注解即开关），
 * 这里全局注册 /api/** 只是让它有机会看到每个请求。</p>
 *
 * <p>ObjectProvider 延迟取 bean：@WebMvcTest 切片不加载 ratelimit 包的 @Component，
 * 构造期强依赖会让全部 web 切片测试崩上下文；生产全量扫描必有该 bean，正常注册。</p>
 */
@Slf4j
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final ObjectProvider<RateLimitInterceptor> rateLimitInterceptorProvider;

    public WebMvcConfig(ObjectProvider<RateLimitInterceptor> rateLimitInterceptorProvider) {
        this.rateLimitInterceptorProvider = rateLimitInterceptorProvider;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        RateLimitInterceptor interceptor = rateLimitInterceptorProvider.getIfAvailable();
        if (interceptor == null) {
            // 仅 @WebMvcTest 切片场景到达；生产环境 bean 必在
            log.debug("RateLimitInterceptor 不在容器(web切片)，跳过注册");
            return;
        }
        registry.addInterceptor(interceptor).addPathPatterns("/api/**");
    }
}
