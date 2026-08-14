package com.superprogrammer.search.util;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.regex.Pattern;

/**
 * 联网搜索安全工具：正文 sanitize + 抓取 SSRF 防护。
 *
 * 两类职责：
 * 1. {@link #sanitizeText(String, int)}：清洗抓来的网页文本（去 HTML 实体残留 / 控制字符 / 折叠空白 / 截断），
 *    防 prompt 注入与 context 噪声——网页正文是不可信输入，注入前必过此关。
 * 2. {@link #assertPublicUrl(String)}：自建引擎直抓网页前校验目标主机 IP 非私有/环回/链路本地段，
 *    挡 SSRF——用户 query 经 SearXNG 拿到的 URL 仍可能诱导抓内网（如 http://10.x / 127.0.0.1）。
 *
 * 局限：仅做解析期 IP 单次解析校验，不防 DNS rebinding（高级攻击，本期不在威胁模型；SearXNG 作出口代理已收口大部分）。
 */
public final class SanitizeUtil {

    private SanitizeUtil() {}

    /** 控制字符（除常见空白 \t\n\r 外的 C0/C1 控制符 + 零宽字符），sanitize 时剔除。 */
    private static final Pattern CONTROL_CHARS = Pattern.compile(
            "[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F-\\u009F\\u200B-\\u200F\\u2028\\u2029]");

    /** 连续空白（含换行）折叠为单空格。 */
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /**
     * 清洗不可信网页文本。
     * 步骤：null→"" → 剔除控制/零宽字符 → 折叠连续空白 → trim → 截断到 maxChars。
     * 不做 HTML 实体解码（Jsoup text() 已解码；这里防的是二次污染）。
     */
    public static String sanitizeText(String raw, int maxChars) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String s = CONTROL_CHARS.matcher(raw).replaceAll("");
        s = WHITESPACE.matcher(s).replaceAll(" ").trim();
        if (maxChars > 0 && s.length() > maxChars) {
            s = s.substring(0, maxChars);
        }
        return s;
    }

    /**
     * SSRF 校验：仅放行 http/https + 解析主机 IP 非私有/环回/链路本地/组播。
     * 任一不满足抛 {@link IllegalArgumentException}，调用方据此跳过抓取（降级 snippet）。
     */
    public static void assertPublicUrl(String url) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("非法 URL: " + url, e);
        }
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("非 http/https scheme: " + url);
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("URL 缺 host: " + url);
        }
        // 解析所有 A/AAAA 记录，任一落私有段即拒（防 happy-path IPv4 公网 + IPv6 内网绕过）。
        InetAddress[] addrs;
        try {
            addrs = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("无法解析主机: " + host, e);
        }
        for (InetAddress addr : addrs) {
            if (addr.isAnyLocalAddress()      // 0.0.0.0 / ::
                    || addr.isLoopbackAddress()  // 127.x / ::1
                    || addr.isLinkLocalAddress() // 169.254.x / fe80::
                    || addr.isSiteLocalAddress() // 10.x / 172.16-31.x / 192.168.x
                    || addr.isMulticastAddress()) {
                throw new IllegalArgumentException("目标命中私有/内网段，拒绝抓取: " + host + " -> " + addr.getHostAddress());
            }
            // 安全体系 S5 · SEC-FR-082（H SSRF）：对齐 SsrfGuard 全量段表——
            // 补 IPv6 唯一本地 fc00::/7（Java isSiteLocalAddress 不覆盖）与 CGNAT 100.64.0.0/10。
            String ip = addr.getHostAddress();
            if (addr instanceof java.net.Inet6Address && ip != null) {
                String lower = ip.toLowerCase();
                int zone = lower.indexOf('%');   // 去 zone id
                if (zone >= 0) lower = lower.substring(0, zone);
                if (lower.startsWith("fc") || lower.startsWith("fd")) {
                    throw new IllegalArgumentException("目标命中 IPv6 唯一本地段，拒绝抓取: " + host + " -> " + ip);
                }
            }
            if (addr instanceof java.net.Inet4Address && ip != null) {
                String[] p = ip.split("\\.");
                if (p.length == 4) {
                    try {
                        int o1 = Integer.parseInt(p[0]);
                        int o2 = Integer.parseInt(p[1]);
                        if (o1 == 100 && o2 >= 64 && o2 <= 127) {
                            throw new IllegalArgumentException("目标命中 CGNAT 保留段，拒绝抓取: " + host + " -> " + ip);
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
    }
}
