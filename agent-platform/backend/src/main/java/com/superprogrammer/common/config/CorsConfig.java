// agent-platform/backend/src/main/java/com/superprogrammer/common/config/CorsConfig.java
package com.superprogrammer.common.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

/**
 * CORS 配置（安全体系 S1 · SEC-FR-003 收紧）。
 *
 * <p>配置项 {@code app.cors.allowed-origins}（逗号分隔精确 Origin 列表）：
 * <ul>
 *   <li><b>已配置（生产）</b>：仅白名单 Origin 放行，禁 {@code *}——精确匹配 + allowCredentials 才安全；</li>
 *   <li><b>未配置（dev 默认）</b>：维持 originPattern=* 宽松，不打断本地联调。</li>
 * </ul>
 * 上线前预发核对：白名单须含全部合法来源（前端域 + 钉钉 H5 回调域），漏配表现为前端跨域报错。
 */
@Configuration
public class CorsConfig {

    /** 逗号分隔的精确 Origin 白名单；空 = dev 宽松模式。 */
    @Value("${app.cors.allowed-origins:}")
    private String allowedOrigins;

    @Bean
    public CorsFilter corsFilter() {
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
        return new CorsFilter(source);
    }
}
