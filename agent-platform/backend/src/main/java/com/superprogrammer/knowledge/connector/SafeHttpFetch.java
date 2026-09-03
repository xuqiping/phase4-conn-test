package com.superprogrammer.knowledge.connector;

import com.superprogrammer.common.exception.BusinessException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.function.Predicate;

/**
 * C6 SSRF 安全 HTTP 取数（WP6 Step2）：**重定向手动跟随**——自动跟随会把校验过的首跳
 * 302 进内网（坑点表头号变体），故 {@code followRedirects(NEVER)}，逐跳 Location 再过
 * urlGuard（生产= {@link com.superprogrammer.common.security.SsrfGuard}）后才请求，≤3 跳防环。
 *
 * <p>urlGuard 以策略注入而非写死 SsrfGuard：单测 mock 源站绑 127.0.0.1（loopback 必被拒），
 * 注入放行 guard 才能测爬取本身；SSRF 语义由 {@code SafeHttpFetchTest} 用真 SsrfGuard 直测。
 */
public final class SafeHttpFetch {

    private static final int MAX_REDIRECTS = 3;

    private SafeHttpFetch() {}

    /** GET（体=byte[]）。每跳过 guard + limiter 限速；非 2xx 抛业务异常（带状态码不带响应体，防内网信息回显）。 */
    public static Fetched get(String url, Predicate<String> urlGuard, FetchLimiter limiter,
                              Map<String, String> headers, Duration timeout) throws IOException, InterruptedException {
        return exchange(url, urlGuard, limiter, headers, timeout, "GET", null);
    }

    /** PROPFIND（WebDAV 枚举，体=String XML）。 */
    public static Fetched propfind(String url, Predicate<String> urlGuard, FetchLimiter limiter,
                                   Map<String, String> headers, String depth,
                                   Duration timeout) throws IOException, InterruptedException {
        return exchange(url, urlGuard, limiter, headers, timeout, "PROPFIND", depth);
    }

    private static Fetched exchange(String url, Predicate<String> urlGuard, FetchLimiter limiter,
                                    Map<String, String> headers, Duration timeout,
                                    String method, String depth) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(timeout)
                .build();
        String current = url;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            // 每跳咽喉点：SSRF 校验先行（拒内网/保留地址/非 http(s)），过闸才允许出站
            if (!urlGuard.test(current)) {
                throw new BusinessException(com.superprogrammer.common.exception.ErrorCode.BAD_REQUEST,
                        "目标地址被 SSRF 防护拒绝");
            }
            limiter.acquire();
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(current))
                    .timeout(timeout)
                    .header("User-Agent", "superprogrammer-kb-connector/1.0");
            if (headers != null) {
                headers.forEach(builder::header);
            }
            if ("PROPFIND".equals(method)) {
                builder.method("PROPFIND", HttpRequest.BodyPublishers.ofString(""));
                if (depth != null) {
                    builder.header("Depth", depth);
                }
            } else {
                builder.GET();
            }
            HttpResponse<byte[]> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            int status = response.statusCode();
            if (status >= 300 && status < 400) {
                String location = response.headers().firstValue("Location").orElse(null);
                if (location == null) {
                    throw new BusinessException(com.superprogrammer.common.exception.ErrorCode.BAD_REQUEST,
                            "重定向缺 Location（HTTP " + status + "）");
                }
                current = URI.create(current).resolve(location).toString();   // 相对 Location 解析
                continue;
            }
            if (status < 200 || status >= 300) {
                throw new BusinessException(com.superprogrammer.common.exception.ErrorCode.BAD_REQUEST,
                        "源站响应 HTTP " + status);
            }
            byte[] body = response.body() == null ? new byte[0] : response.body();
            limiter.chargeBytes(body.length);
            return new Fetched(current, status, response.headers(), body);
        }
        throw new BusinessException(com.superprogrammer.common.exception.ErrorCode.BAD_REQUEST,
                "重定向超过 " + MAX_REDIRECTS + " 跳，疑似重定向环");
    }

    /** 一次成功取数：finalUrl=重定向落点（externalId 应以最终 URL 记账），headers 供 etag 提取。 */
    public record Fetched(String finalUrl, int status, java.net.http.HttpHeaders headers, byte[] body) {}
}
