package com.superprogrammer.media.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 媒体生成配置（运维开关 + 上限，spec §4 性能/安全）。
 *
 * <p>用法：{@code media.gen-enabled}(默认 true) 总开关；{@code media.max-duration}(默认 15 秒，官方区间 4–15)、
 * {@code media.max-res}(默认 4K) 控盘与成本上限（capability 为逐模型真闸门，此为第二道全局兜底）；
 * {@code media.poll-ms}(默认 5000) worker 轮询间隔；{@code media.lock-minutes}(默认 5) 认领锁时长。
 * 业务任务不设总超时；单次 HTTP 连接/响应超时由 Provider 客户端负责。
 */
@Data
@Component
@ConfigurationProperties(prefix = "media")
public class MediaGenProperties {

    /** Ark 拉取参考视频所需的短期签名公网地址配置。 */
    private Reference reference = new Reference();

    @Data
    public static class Reference {
        /** Ark 可访问的 HTTPS 应用根地址，例如 https://media.example.com。 */
        private String publicBaseUrl = "";
        /** HMAC-SHA256 签名密钥，只能由环境变量注入。 */
        private String signingKey = "";
        /** 单个参考视频 URL 的有效期，默认 15 分钟。 */
        private long ttlSeconds = 900;
    }

    public boolean isReferenceVideoConfigured() {
        String baseUrl = reference == null ? null : reference.getPublicBaseUrl();
        String signingKey = reference == null ? null : reference.getSigningKey();
        if (baseUrl == null || signingKey == null || signingKey.length() < 32
                || reference.getTtlSeconds() <= 0) {
            return false;
        }
        try {
            java.net.URI uri = java.net.URI.create(baseUrl.strip());
            String host = uri.getHost();
            return "https".equalsIgnoreCase(uri.getScheme())
                    && host != null && !host.isBlank()
                    && !"localhost".equalsIgnoreCase(host)
                    && !host.startsWith("127.") && !"::1".equals(host);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** 总开关。false 时 submit 直接拒绝（功能降级）。 */
    private boolean genEnabled = true;

    /**
     * 视频 provider 名称（在「全局模型供应商」里以此 name 建一条 category=VIDEO 的 provider）。
     * 与 chat 的 doubao 解耦：chat 走 CHAT 行（doubao），视频走 VIDEO 行（seedance，各自独立 endpoint/key/model）。
     */
    private String providerName = "seedance";

    /** 时长上限（秒）。官方 SeedDance 2.0 区间 4–15，默认 15。 */
    private int maxDuration = 15;

    /**
     * 分辨率全局上限（双重校验的第二道闸：先 capability.supportedResolutions 按模型白名单，
     * 再此处按全局 cost 上限）。默认 "4K"——以 capability 为真正的逐模型闸门：
     * 不支持 4K 的模型在第一道闸即被拒（其 supportedResolutions 不含 4K），故全局默认放宽到
     * 顶格不会越权放开低能力模型。原默认 720p 会误杀 1080p/4K（rank 比较 > 720p 即拒）。
     * 如需成本兜底，运维可设 {@code media.max-res=1080p} 收紧。
     */
    private String maxRes = "4K";

    /** worker 轮询间隔（ms）。 */
    private long pollMs = 5000;

    /** 认领锁时长（分钟），过期后 RUNNING 行可被重新认领（崩溃恢复）。 */
    private int lockMinutes = 5;

    /** 退避轮询起始间隔（ms）。 */
    private long backoffStartMs = 5000;

    /** 退避轮询封顶间隔（ms）。 */
    private long backoffCapMs = 30000;
}
