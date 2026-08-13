package com.superprogrammer.common.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 客户端 IP 解析器（11x 加固 · P1-C2）。
 *
 * <p><b>XFF 不可信红线</b>（沉淀规范 · 安全中间件信任红线）：X-Forwarded-For 是客户端可伪造头，
 * 仅当 remoteAddr 命中可信代理白名单（{@code app.security.trusted-proxies}，逗号分隔精确 IP）
 * 才采 XFF 首段，否则用 remoteAddr。与 {@code AuthService.getClientIp} 同范式。</p>
 *
 * <p>供限流拦截器 / SecurityGateFilter / IP 黑名单等安全决策点使用
 * （MdcUserFilter 的日志 IP 无信任校验，仅作展示，不可复用于安全判定）。</p>
 */
@Component
public class ClientIpResolver {

    /** 可信代理网段/精确 IP 白名单（逗号分隔）。空=不信任任何代理，一律用 remoteAddr。 */
    @Value("${app.security.trusted-proxies:}")
    private String trustedProxies;

    /**
     * 取真实客户端 IP。
     * remoteAddr 命中可信代理 → 采 XFF 首段；否则 → remoteAddr。
     */
    public String resolve(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        if (isTrustedProxy(remoteAddr)) {
            String xff = request.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                String first = xff.split(",")[0].trim();
                if (!first.isBlank()) {
                    return first;
                }
            }
        }
        return remoteAddr;
    }

    private boolean isTrustedProxy(String remoteAddr) {
        if (remoteAddr == null || trustedProxies == null || trustedProxies.isBlank()) {
            return false;
        }
        for (String p : trustedProxies.split(",")) {
            if (remoteAddr.equals(p.trim())) {
                return true;
            }
        }
        return false;
    }
}
