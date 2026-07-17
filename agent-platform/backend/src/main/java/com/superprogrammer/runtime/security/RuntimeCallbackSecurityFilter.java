// agent-platform/backend/src/main/java/com/superprogrammer/runtime/security/RuntimeCallbackSecurityFilter.java
package com.superprogrammer.runtime.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Sidecar 回调端点共享密钥校验（安全审计 #1）。
 * <p>{@code /api/runtime/callbacks/**} 在 {@link com.superprogrammer.auth.security.SecurityConfig} 中
 * permitAll（sidecar 无用户 JWT），此前「信任 sidecar」未落实成任何凭据 → 任何人可冒充 sidecar 调回调，
 * 用请求体自填 userId 越权检索他人知识库 / 触发他人 Agent。
 * <p>本过滤器要求出站请求带 Header {@code X-Runtime-Token: <预共享密钥>}，与 {@code runtime.callback.token}
 * 比对；缺失/不符 → 401。密钥未配置 → fail-closed（拒绝所有），防忘配后静默裸奔。
 * <p>userId 越权在 {@code RuntimeNodeCallbackService} 里靠 executionId → triggeredBy 反查兜底。
 * <p>非 @Component：仅由 SecurityConfig 注册进 Security 链，避免被 Spring Boot 当 servlet filter 自动注册导致重复执行。
 */
@Slf4j
public class RuntimeCallbackSecurityFilter extends OncePerRequestFilter {

    public static final String TOKEN_HEADER = "X-Runtime-Token";
    private static final String CALLBACK_PATH_PREFIX = "/api/runtime/callbacks/";

    private final String expectedToken;

    public RuntimeCallbackSecurityFilter(String expectedToken) {
        this.expectedToken = expectedToken;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (path == null || !path.startsWith(CALLBACK_PATH_PREFIX)) {
            chain.doFilter(request, response);
            return;
        }
        if (expectedToken == null || expectedToken.isBlank()) {
            log.error("RUNTIME_CALLBACK_TOKEN 未配置，回调端点 fail-closed 拒绝请求 path={}", path);
            writeUnauthorized(response, "回调端点未配置共享密钥");
            return;
        }
        String token = request.getHeader(TOKEN_HEADER);
        if (token == null || !expectedToken.equals(token)) {
            log.warn("回调凭据无效 path={} ip={}", path, request.getRemoteAddr());
            writeUnauthorized(response, "无效的回调凭据");
            return;
        }
        chain.doFilter(request, response);
    }

    private void writeUnauthorized(HttpServletResponse response, String msg) throws IOException {
        response.setContentType("application/json;charset=UTF-8");
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.getWriter().write("{\"code\":401,\"message\":\"" + msg + "\"}");
    }
}
