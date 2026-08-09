// agent-platform/backend/src/main/java/com/superprogrammer/common/config/CorsConfig.java
package com.superprogrammer.common.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * CORS 配置（安全体系 S1 · SEC-FR-003 收紧）。
 *
 * <p>配置项 {@code app.cors.allowed-origins}（逗号分隔精确 Origin 列表）：
 * <ul>
 *   <li><b>已配置（生产）</b>：仅白名单 Origin 放行，禁 {@code *}——精确匹配 + allowCredentials 才安全；</li>
 *   <li><b>未配置（dev 默认）</b>：维持 originPattern=* 宽松，不打断本地联调。</li>
 * </ul>
 * 上线前预发核对：白名单须含全部合法来源（前端域 + 钉钉 H5 域），漏配表现为前端跨域报错。
 *
 * <p>Phase4 审查修正：独立 CorsFilter @Bean 默认排在 springSecurityFilterChain（序 -100）之后，
 * 预检 OPTIONS（规范不带凭证）打到需认证端点先被安全链 401，CorsFilter 根本没机会执行——
 * 白名单「放行」语义从未真实生效（只有「拒」灵）。故改为暴露 {@link CorsConfigurationSource}
 * 由 SecurityConfig {@code http.cors()} 内联进安全链最前端，预检在授权判定前处理。
 */
@Configuration
public class CorsConfig {

    /** 逗号分隔的精确 Origin 白名单；空 = dev 宽松模式。 */
    @Value("${app.cors.allowed-origins:}")
    private String allowedOrigins;

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowCredentials(true);
        if (allowedOrigins != null && !allowedOrigins.isBlank()) {
            // 生产白名单：精确 Origin（不能用 pattern，配合 credentials 才有约束力）
            for (String origin : allowedOrigins.split(",")) {
                String trimmed = origin.trim();
                if (!trimmed.isEmpty()) {
                    config.addAllowedOrigin(trimmed);
                }
            }
        } else {
            config.addAllowedOriginPattern("*");
        }
        config.addAllowedHeader("*");
        config.addAllowedMethod("*");
        config.addExposedHeader("Authorization");
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
