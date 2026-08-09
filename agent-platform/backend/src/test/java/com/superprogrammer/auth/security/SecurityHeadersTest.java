package com.superprogrammer.auth.security;

import com.superprogrammer.auth.service.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 安全体系 S1 · SEC-FR-002 安全响应头测试（真 SecurityFilterChain，与 SecurityConfigAsyncDispatchTest 同模式）。
 */
@WebMvcTest(SecurityHeadersTest.ProbeController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, SecurityHeadersTest.ProbeController.class})
class SecurityHeadersTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtUtil jwtUtil;

    @MockBean
    private AuthService authService;

    // AC-SEC-FR-002：四安全头齐出（未认证 401 响应同样带头——HeaderWriterFilter 在入口点前写入）
    @Test
    void securityHeadersPresentOnEveryResponse() throws Exception {
        mockMvc.perform(get("/headers-probe"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"))
                .andExpect(header().string("Content-Security-Policy",
                        "default-src 'self'; style-src 'self' 'unsafe-inline'; "
                                + "img-src 'self' data: blob:; media-src 'self' blob:"));
    }

    @RestController
    static class ProbeController {
        @GetMapping("/headers-probe")
        String probe() {
            return "ok";
        }
    }
}
