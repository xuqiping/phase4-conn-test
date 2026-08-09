package com.superprogrammer.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 安全体系 S1 · SEC-FR-003 CORS 收紧测试：白名单模式精确放行 / 空配置 dev 宽松。
 * <p>Phase4 审查修正：CORS 内联进安全链后 bean 从独立 CorsFilter 改为
 * {@link CorsConfig#corsConfigurationSource()}（预检须在授权判定前处理），断言目标同步换。
 */
class CorsConfigTest {

    private CorsConfiguration configWith(String allowedOrigins) {
        CorsConfig cors = new CorsConfig();
        ReflectionTestUtils.setField(cors, "allowedOrigins", allowedOrigins);
        UrlBasedCorsConfigurationSource source =
                (UrlBasedCorsConfigurationSource) cors.corsConfigurationSource();
        return source.getCorsConfigurations().get("/**");
    }

    // AC-SEC-FR-003：配置白名单 → 精确 Origin，禁 *
    @Test
    void whitelistMode_usesExactOrigins() {
        CorsConfiguration config = configWith("https://app.example.com, https://h5.dingtalk.com");

        assertThat(config.getAllowedOrigins())
                .containsExactly("https://app.example.com", "https://h5.dingtalk.com");
        assertThat(config.getAllowedOriginPatterns()).isNull();
        assertThat(config.getAllowCredentials()).isTrue();
    }

    // AC-SEC-FR-003：空配置 → dev 宽松 originPattern=*（不打断本地联调）
    @Test
    void blankConfig_devPermissive() {
        CorsConfiguration config = configWith("");

        assertThat(config.getAllowedOriginPatterns()).containsExactly("*");
        assertThat(config.getAllowedOrigins()).isNull();
    }
}
