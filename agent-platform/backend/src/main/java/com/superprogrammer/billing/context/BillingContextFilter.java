package com.superprogrammer.billing.context;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 请求入口种入 {@link BillingContext}：从 SecurityContext principal（= Long userId，
 * 见 JwtAuthenticationFilter）取当前用户，让同请求线程内所有 LLM/媒体调用自动归户计费。
 *
 * <p>排在 {@code JwtAuthenticationFilter} 之后（SecurityConfig addFilterAfter），确保 principal 已就位。
 * 白名单/匿名请求（principal 非 Long）不种入 → current()=null → 仅采不扣。
 *
 * <p>finally 清除——Tomcat 线程复用，防用户身份串号。
 */
@Component
public class BillingContextFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Long userId = resolveUserId();
        if (userId != null) {
            BillingContext.set(userId);
        }
        try {
            filterChain.doFilter(request, response);
        } finally {
            BillingContext.clear();
        }
    }

    /** principal 为 Long userId（JwtAuthenticationFilter 三参构造）；否则（匿名/未认证）返 null。 */
    private Long resolveUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        Object principal = auth.getPrincipal();
        return principal instanceof Long uid ? uid : null;
    }
}
