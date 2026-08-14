package com.superprogrammer.chat.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 安全体系 S5 · SEC-FR-025（C5）：WS 握手 Origin 白名单解析。
 * 原 setAllowedOrigins("*") = 任意站点可发起跨站 WS 握手（CSWSH）。
 */
class WebSocketConfigTest {

    @Test
    @DisplayName("未配置（null/blank）→ dev 回退 {*}")
    void blankConfig_fallsBackToWildcard() {
        assertThat(WebSocketConfig.resolveAllowedOrigins(null)).containsExactly("*");
        assertThat(WebSocketConfig.resolveAllowedOrigins("   ")).containsExactly("*");
    }

    @Test
    @DisplayName("配置逗号分隔 Origin → 精确列表（trim 空段）")
    void configuredList_parsedExactly() {
        assertThat(WebSocketConfig.resolveAllowedOrigins("https://app.example.com"))
                .containsExactly("https://app.example.com");
        assertThat(WebSocketConfig.resolveAllowedOrigins("https://a.com, https://b.com ,https://c.com"))
                .containsExactly("https://a.com", "https://b.com", "https://c.com");
    }

    @Test
    @DisplayName("误配纯逗号 → fail-closed 空数组（握手全拒，不静默回退 *）")
    void garbageConfig_failClosed() {
        assertThat(WebSocketConfig.resolveAllowedOrigins(",, ,")).isEmpty();
    }
}
