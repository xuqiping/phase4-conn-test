package com.superprogrammer.common.logging;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MdcUserFilter 单测（LOG-FR-03 / 安全检查：MDC 清理防串号）。
 * principal 约定：userId(Long)=principal、username=credentials（见 JwtAuthenticationFilter）。
 */
class MdcUserFilterTest {

    private final MdcUserFilter filter = new MdcUserFilter();

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        MDC.clear();
    }

    @Test
    void loggedInUserEntersMdcAndIsRemovedAfter() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(42L, "alice", List.of()));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/chat");
        request.setRemoteAddr("10.0.0.9");

        AtomicReference<String> seenUser = new AtomicReference<>();
        AtomicReference<String> seenIp = new AtomicReference<>();
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                seenUser.set(MDC.get("userId"));
                seenIp.set(MDC.get("clientIp"));
            }
        };
        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(seenUser.get()).isEqualTo("42");
        assertThat(seenIp.get()).isEqualTo("10.0.0.9");
        // finally 清理：下一条请求复用线程不串号
        assertThat(MDC.get("userId")).isNull();
        assertThat(MDC.get("clientIp")).isNull();
    }

    @Test
    void anonymousGetsDashAndXffFirstHop() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/auth/login");
        request.addHeader("X-Forwarded-For", "203.0.113.7, 10.0.0.1");

        AtomicReference<String> seenUser = new AtomicReference<>();
        AtomicReference<String> seenIp = new AtomicReference<>();
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                seenUser.set(MDC.get("userId"));
                seenIp.set(MDC.get("clientIp"));
            }
        };
        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(seenUser.get()).isEqualTo("-");
        assertThat(seenIp.get()).isEqualTo("203.0.113.7");
    }

    @Test
    void doesNotClobberTraceId() throws Exception {
        // traceId 由外层 micrometer scope 管理：本过滤器 finally 不得抹掉它
        MDC.put("traceId", "trace-keep");
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(new MockHttpServletRequest("GET", "/api/x"), new MockHttpServletResponse(), chain);
        assertThat(MDC.get("traceId")).isEqualTo("trace-keep");
    }
}
