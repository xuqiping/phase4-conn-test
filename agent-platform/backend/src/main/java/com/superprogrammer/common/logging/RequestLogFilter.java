package com.superprogrammer.common.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 请求耗时摘要（日志系统 LOG-FR-06）：每请求恰好一行 INFO 摘要。
 *
 * <p>字段：method uri status costMs；userId/traceId/clientIp 由 MDC 自动带入
 * （注册顺序在 {@link MdcUserFilter} 之后，摘要行打出时 MDC 仍在——其 finally 在外层后执行）。
 * <p><b>PII 红线</b>：明确不读 body、不记 query 参数原文以外的内容；uri 用 getRequestURI（不含 query，
 * query 可能带敏感参数原文）。
 */
@Slf4j
@Component
public class RequestLogFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long startNanos = System.nanoTime();
        try {
            filterChain.doFilter(request, response);
        } finally {
            long costMs = (System.nanoTime() - startNanos) / 1_000_000;
            log.info("request {} {} status={} costMs={}",
                    request.getMethod(), request.getRequestURI(), response.getStatus(), costMs);
        }
    }
}
