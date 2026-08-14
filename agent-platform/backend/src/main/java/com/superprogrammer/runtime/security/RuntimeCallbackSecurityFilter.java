// agent-platform/backend/src/main/java/com/superprogrammer/runtime/security/RuntimeCallbackSecurityFilter.java
package com.superprogrammer.runtime.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.function.Supplier;

/**
 * Sidecar 回调端点共享密钥校验（安全审计 #1 → 安全体系 S5 · SEC-FR-061 升级 F2 防重放）。
 * <p>{@code /api/runtime/callbacks/**} 在 {@link com.superprogrammer.auth.security.SecurityConfig} 中
 * permitAll（sidecar 无用户 JWT），此前「信任 sidecar」未落实成任何凭据 → 任何人可冒充 sidecar 调回调，
 * 用请求体自填 userId 越权检索他人知识库 / 触发他人 Agent。
 * <p><b>静态 token 层（原有）</b>：Header {@code X-Runtime-Token: <预共享密钥>} 与
 * {@code runtime.callback.token} 恒定时间比对；缺失/不符 → 401。密钥未配置 → fail-closed。
 * <p><b>HMAC 验签层（S5 新增）</b>：请求带 {@code X-Callback-Timestamp}（epoch 毫秒）+
 * {@code X-Callback-Signature = hex(HMAC-SHA256(token, ts + "." + body))}。验签通过即证明持有
 * 密钥（无需再比对静态头）且 ts 距今 ≤300s——抓包重放的旧签名过期即拒，封重放窗口。
 * <p><b>双轨兼容</b>（{@code security.runtime.callback.hmac-mode} 热更）：
 * <ul>
 *   <li><b>DUAL（默认）</b>：带签名头 → 验 HMAC（错签/过期 ts 均 401）；不带 → 回落静态 token
 *   （旧 sidecar 分批发布期间不断流）；</li>
 *   <li><b>ENFORCE</b>：不带签名头直接 401（sidecar 全量升级后收紧）。</li>
 * </ul>
 * userId 越权在 {@code RuntimeNodeCallbackService} 里靠 executionId → triggeredBy 反查兜底。
 * <p>非 @Component：仅由 SecurityConfig 注册进 Security 链，避免被 Spring Boot 当 servlet filter 自动注册导致重复执行。
 */
@Slf4j
public class RuntimeCallbackSecurityFilter extends OncePerRequestFilter {

    public static final String TOKEN_HEADER = "X-Runtime-Token";
    public static final String TIMESTAMP_HEADER = "X-Callback-Timestamp";
    public static final String SIGNATURE_HEADER = "X-Callback-Signature";
    private static final String CALLBACK_PATH_PREFIX = "/api/runtime/callbacks/";
    /** 时间窗 ±300s：sidecar 与 backend 时钟偏差容忍 + 重放窗口上限。 */
    private static final long MAX_CLOCK_SKEW_MS = 300_000L;

    private final String expectedToken;
    /** HMAC 模式热更（"DUAL"/"ENFORCE"），每次请求现查 system_settings——Supplier 解耦便于单测。 */
    private final Supplier<String> hmacModeSupplier;
    /** 可选依赖：metrics bean 缺席（切片测试）时跳过计数，不影响鉴权。 */
    private final com.superprogrammer.common.metrics.BizMetrics bizMetrics;

    public RuntimeCallbackSecurityFilter(String expectedToken, Supplier<String> hmacModeSupplier,
                                         com.superprogrammer.common.metrics.BizMetrics bizMetrics) {
        this.expectedToken = expectedToken;
        this.hmacModeSupplier = hmacModeSupplier;
        this.bizMetrics = bizMetrics;
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
        String timestamp = request.getHeader(TIMESTAMP_HEADER);
        String signature = request.getHeader(SIGNATURE_HEADER);
        if (timestamp != null && signature != null) {
            verifyHmac(request, response, chain, path, timestamp, signature);
            return;
        }
        // 无签名头 → 双轨判定：ENFORCE 拒；DUAL 回落静态 token（发布兼容期）
        if (isEnforceMode()) {
            log.warn("回调缺少 HMAC 签名头且处于 enforce 模式 path={} ip={}", path, request.getRemoteAddr());
            metric("rejected");
            writeUnauthorized(response, "回调缺少签名（enforce 模式）");
            return;
        }
        String token = request.getHeader(TOKEN_HEADER);
        if (token == null || !constantTimeEquals(expectedToken, token)) {
            log.warn("回调凭据无效 path={} ip={}", path, request.getRemoteAddr());
            metric("rejected");
            writeUnauthorized(response, "无效的回调凭据");
            return;
        }
        metric("legacy");
        chain.doFilter(request, response);
    }

    private void verifyHmac(HttpServletRequest request, HttpServletResponse response, FilterChain chain,
                            String path, String timestamp, String signature) throws ServletException, IOException {
        long ts;
        try {
            ts = Long.parseLong(timestamp.trim());
        } catch (NumberFormatException e) {
            log.warn("回调时间戳非法 path={} ts={}", path, timestamp);
            metric("rejected");
            writeUnauthorized(response, "回调时间戳非法");
            return;
        }
        if (Math.abs(System.currentTimeMillis() - ts) > MAX_CLOCK_SKEW_MS) {
            log.warn("回调时间戳超出 ±300s 窗口（疑似重放）path={} ts={} ip={}", path, ts, request.getRemoteAddr());
            metric("rejected");
            writeUnauthorized(response, "回调时间戳过期");
            return;
        }
        byte[] body;
        try (InputStream in = request.getInputStream()) {
            body = in.readAllBytes();
        } catch (IOException e) {
            log.error("回调读取请求体失败 path={}", path, e);
            metric("rejected");
            writeUnauthorized(response, "回调请求体读取失败");
            return;
        }
        String signedPayload = timestamp.trim() + "." + new String(body, StandardCharsets.UTF_8);
        String expected = hmacSha256Hex(expectedToken, signedPayload);
        if (!constantTimeEquals(expected, signature.trim().toLowerCase())) {
            log.warn("回调 HMAC 验签失败 path={} ip={}", path, request.getRemoteAddr());
            metric("rejected");
            writeUnauthorized(response, "回调签名无效");
            return;
        }
        metric("hmac");
        // 验签消费了原始流 → 包一层可重复读的请求体再放行下游
        chain.doFilter(new BodyCachingRequest(request, body), response);
    }

    private boolean isEnforceMode() {
        try {
            return "ENFORCE".equalsIgnoreCase(hmacModeSupplier.get());
        } catch (Exception e) {
            // 模式读取失败（如 settings 表抖动）→ 按 DUAL 处理，检测层不自残
            log.warn("读取 callback hmac-mode 失败，按 DUAL 处理", e);
            return false;
        }
    }

    private void metric(String result) {
        if (bizMetrics != null) {
            bizMetrics.callbackAuth(result);
        }
    }

    /** 恒定时间字符串比对（防时序侧信道逐字节探密钥）。 */
    static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    /** HMAC-SHA256(key, data) → 小写 hex。 */
    static String hmacSha256Hex(String key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] out = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(out.length * 2);
            for (byte b : out) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 不可用", e);
        }
    }

    private void writeUnauthorized(HttpServletResponse response, String msg) throws IOException {
        response.setContentType("application/json;charset=UTF-8");
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.getWriter().write("{\"code\":401,\"message\":\"" + msg + "\"}");
    }

    /** 可重复读请求体包装：验签已消费原始流，缓存字节重新供给下游 servlet/controller。 */
    static final class BodyCachingRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        BodyCachingRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream buf = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return buf.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    // 同步读取，无需异步监听
                }

                @Override
                public int read() {
                    return buf.read();
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }
}
