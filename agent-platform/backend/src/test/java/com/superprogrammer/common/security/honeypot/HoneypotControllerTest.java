package com.superprogrammer.common.security.honeypot;

import com.superprogrammer.common.config.TestSecurityConfig;
import com.superprogrammer.common.metrics.BizMetrics;
import com.superprogrammer.common.security.ClientIpResolver;
import com.superprogrammer.common.security.SecurityEventService;
import com.superprogrammer.system.service.SystemSettingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 安全体系 S5 · SEC-FR-133（M4 蜜罐）：canary 端点 404 伪装 + HIGH 事件 + 开关降级。
 */
@WebMvcTest(HoneypotController.class)
@Import(TestSecurityConfig.class)
class HoneypotControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean private SecurityEventService securityEventService;
    @MockBean private SystemSettingService systemSettingService;
    @MockBean private ClientIpResolver clientIpResolver;
    @MockBean private BizMetrics bizMetrics;
    /** 切片会装载 JwtAuthenticationFilter（Filter bean），补齐其构造依赖 */
    @MockBean private com.superprogrammer.auth.security.JwtUtil jwtUtil;
    @MockBean private com.superprogrammer.auth.service.AuthService authService;
    @MockBean private com.superprogrammer.auth.service.SessionService sessionService;

    @Test
    @DisplayName("命中 /.env → 404 伪装（Spring 同构 JSON）+ KIND_HONEYPOT HIGH 事件")
    void envHit_returns404AndRecordsHighEvent() throws Exception {
        when(systemSettingService.getHoneypotEnabled()).thenReturn(true);
        when(clientIpResolver.resolve(any())).thenReturn("203.0.113.9");

        mockMvc.perform(get("/.env"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.path").value("/.env"));

        verify(securityEventService).record(
                eq("HONEYPOT"), eq("HIGH"), eq(null), eq("203.0.113.9"),
                eq("HONEYPOT"), contains("/.env"), eq("NONE"));
        verify(bizMetrics).honeypotHit("/.env");
    }

    @Test
    @DisplayName("四条 canary 路由全部 404")
    void allCanaryPaths_return404() throws Exception {
        when(systemSettingService.getHoneypotEnabled()).thenReturn(false);
        for (String path : new String[]{"/wp-admin", "/.env", "/.git/config", "/api/admin/config.php"}) {
            mockMvc.perform(get(path)).andExpect(status().isNotFound());
        }
    }

    @Test
    @DisplayName("开关关 → 纯 404 不告警（误报降级）")
    void disabled_noEventRecorded() throws Exception {
        when(systemSettingService.getHoneypotEnabled()).thenReturn(false);

        mockMvc.perform(get("/wp-admin")).andExpect(status().isNotFound());

        verify(securityEventService, never()).record(anyString(), anyString(), any(), anyString(),
                anyString(), anyString(), anyString());
        verify(bizMetrics, never()).honeypotHit(anyString());
    }

    @Test
    @DisplayName("事件落库异常 → 404 响应不受影响（检测层不自残）")
    void recordFailure_still404() throws Exception {
        when(systemSettingService.getHoneypotEnabled()).thenReturn(true);
        when(clientIpResolver.resolve(any())).thenReturn("1.2.3.4");
        doThrow(new RuntimeException("db down"))
                .when(securityEventService).record(anyString(), anyString(), any(), anyString(),
                        anyString(), anyString(), anyString());

        mockMvc.perform(get("/.git/config"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Not Found"));
    }

    @Test
    @DisplayName("detail 含 userAgent（扫描器画像）")
    void detailContainsUserAgent() throws Exception {
        when(systemSettingService.getHoneypotEnabled()).thenReturn(true);
        when(clientIpResolver.resolve(any())).thenReturn("198.51.100.7");

        mockMvc.perform(get("/api/admin/config.php")
                        .header("User-Agent", "masscan/1.3"))
                .andExpect(status().isNotFound());

        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(securityEventService).record(eq("HONEYPOT"), eq("HIGH"), eq(null), eq("198.51.100.7"),
                eq("HONEYPOT"), detail.capture(), eq("NONE"));
        org.assertj.core.api.Assertions.assertThat(detail.getValue()).contains("masscan/1.3");
    }
}
