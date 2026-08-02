// agent-platform/backend/src/main/java/com/superprogrammer/common/security/SsrfGuard.java
package com.superprogrammer.common.security;

import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * SSRF 防护（安全审计 #3）：校验外发 URL 不指向内网/保留/云元数据地址。
 * <p>用户可自填 LLM endpoint → 服务器主动请求之；此前无限制，可填
 * {@code http://169.254.169.254/...}（云元数据）/ {@code http://10.0.0.5/admin}（内网管理面），
 * 借服务器的手访问内部资源（SSRF）。
 * <p>本工具在 provider 实例化（单一咽喉点）校验：协议限 http/https，解析主机全部地址，
 * 命中回环/私网/链路本地/多播/CGNAT/IPv6-ULA → 拒绝。
 * <p>残留风险：DNS rebinding（校验时 A 记录公网，请求时改内网）需连接期 IP 绑定才完全防；
 * 本实现覆盖文档列出的直接填内网地址场景。
 */
public final class SsrfGuard {

    private SsrfGuard() {}

    public static void validate(String url) {
        if (url == null || url.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "URL 不能为空");
        }
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "URL 格式无效");
        }
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "仅允许 http/https 协议");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "URL 缺少主机名");
        }
        InetAddress[] addrs;
        try {
            addrs = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "无法解析主机：" + host);
        }
        for (InetAddress addr : addrs) {
            if (isBlocked(addr)) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "禁止访问内网/保留地址：" + host);
            }
        }
    }

    private static boolean isBlocked(InetAddress addr) {
        if (addr.isAnyLocalAddress()        // 0.0.0.0 / ::
                || addr.isLoopbackAddress() // 127.0.0.0/8, ::1
                || addr.isLinkLocalAddress()// 169.254.0.0/16（云元数据）, fe80::/10
                || addr.isSiteLocalAddress()// 10/8, 172.16/12, 192.168/16
                || addr.isMulticastAddress()) {
            return true;
        }
        String ip = addr.getHostAddress();
        // IPv6 唯一本地地址 fc00::/7（Java isSiteLocalAddress 不覆盖 IPv6 ULA）
        if (addr instanceof Inet6Address && ip != null) {
            String lower = ip.toLowerCase();
            int colon = lower.indexOf('%');   // 去掉 zone id
            if (colon >= 0) lower = lower.substring(0, colon);
            if (lower.startsWith("fc") || lower.startsWith("fd")) return true;
        }
        // CGNAT 100.64.0.0/10（运营商级 NAT，常被当内网）
        if (addr instanceof Inet4Address && ip != null) {
            String[] p = ip.split("\\.");
            if (p.length == 4) {
                try {
                    int o1 = Integer.parseInt(p[0]);
                    int o2 = Integer.parseInt(p[1]);
                    if (o1 == 100 && o2 >= 64 && o2 <= 127) return true;
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return false;
    }
}
