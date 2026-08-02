package com.superprogrammer.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 请求日志过滤器：记录进入后端的请求摘要，便于排查联调问题和安全审计。
 * 可通过配置项 `file-keeper.request-logging.enabled` 开关，默认关闭以避免生产环境日志膨胀。
 */
@Component
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger("REQUEST_LOGGER");

    @Value("${file-keeper.request-logging.enabled:false}")
    private boolean enabled;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!enabled) {
            filterChain.doFilter(request, response);
            return;
        }

        String method = request.getMethod();
        String uri = request.getRequestURI();
        String query = request.getQueryString();
        String origin = request.getHeader("Origin");
        String auth = request.getHeader("Authorization");
        String remote = request.getRemoteAddr();

        log.info(">>> REQUEST {} {}{} origin={} auth={} remote={}",
                method,
                uri,
                query == null ? "" : "?" + query,
                origin,
                auth == null ? "none" : auth.substring(0, Math.min(20, auth.length())) + "...",
                remote);

        try {
            filterChain.doFilter(request, response);
        } catch (Exception ex) {
            log.error(">>> REQUEST FAILED {} {} : {}", method, uri, ex.toString());
            throw ex;
        } finally {
            log.info("<<< RESPONSE {} {} status={}", method, uri, response.getStatus());
        }
    }
}
