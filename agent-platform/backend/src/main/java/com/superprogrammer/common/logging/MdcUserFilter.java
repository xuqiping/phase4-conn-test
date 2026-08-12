package com.superprogrammer.common.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 用户上下文入 MDC（日志系统 LOG-FR-03）：每条日志带 userId/username/clientIp。
 *
 * <p>注册顺序：Security 链内、{@code JwtAuthenticationFilter} 之后（principal 已就位）。
 * principal 约定 = userId(Long)，credentials = username（见 JwtAuthenticationFilter）。
 * 未登录/匿名（principal 非 Long）填 "-"，日志不空段。
 *
 * <p><b>防串号</b>：finally 只 remove 自己写的三个 key，<b>不 MDC.clear()</b>——
 * traceId/spanId 由外层 micrometer Observation scope 管理，clear 会把它们一并抹掉，
 * 导致本过滤器之后的日志（含 RequestLogFilter 摘要行）丢 traceId。
 */
@Slf4j
@Component
public class MdcUserFilter extends OncePerRequestFilter {

    public static final String MDC_USER_ID = "userId";
    public static final String MDC_USERNAME = "username";
    public static final String MDC_CLIENT_IP = "clientIp";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof Long userId) {
                MDC.put(MDC_USER_ID, String.valueOf(userId));
                if (auth.getCredentials() instanceof String username) {
                    MDC.put(MDC_USERNAME, username);
                }
            } else {
                MDC.put(MDC_USER_ID, "-");
            }
            MDC.put(MDC_CLIENT_IP, clientIp(request));
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_USER_ID);
            MDC.remove(MDC_USERNAME);
            MDC.remove(MDC_CLIENT_IP);
        }
    }

    /** 代理链路取 X-Forwarded-For 首段（客户端原始 IP），无则 remoteAddr。 */
    private String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        return request.getRemoteAddr();
    }
}
