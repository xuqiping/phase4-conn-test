package com.superprogrammer.runtime.security;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 安全体系 S5 · SEC-FR-061（F2）：sidecar 回调 HMAC 防重放 + 双轨兼容。
 * 签名契约：X-Callback-Signature = hex(HMAC-SHA256(token, ts + "." + body))，±300s 时间窗。
 */
class RuntimeCallbackSecurityFilterTest {

    private static final String TOKEN = "test-callback-secret";
    private static final String BODY = "{\"executionId\":\"1001\",\"sourceType\":\"SKILL\"}";
    private static final String CALLBACK_PATH = "/api/runtime/callbacks/nodes/execute";

    private final RuntimeCallbackSecurityFilter dualFilter =
            new RuntimeCallbackSecurityFilter(TOKEN, () -> "DUAL", null);
    private final RuntimeCallbackSecurityFilter enforceFilter =
            new RuntimeCallbackSecurityFilter(TOKEN, () -> "ENFORCE", null);

    private MockHttpServletRequest request(String path, String body) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", path);
        req.setContent(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return req;
    }

    private static String sign(String token, String ts, String body) {
        return RuntimeCallbackSecurityFilter.hmacSha256Hex(token, ts + "." + body);
    }

    @Test
    @DisplayName("HMAC 签名正确 → 放行（不再要求静态 token 头）")
    void hmac_valid_passes() throws ServletException, IOException {
        String ts = String.valueOf(System.currentTimeMillis());
        MockHttpServletRequest req = request(CALLBACK_PATH, BODY);
        req.addHeader(RuntimeCallbackSecurityFilter.TIMESTAMP_HEADER, ts);
        req.addHeader(RuntimeCallbackSecurityFilter.SIGNATURE_HEADER, sign(TOKEN, ts, BODY));
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        dualFilter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    @DisplayName("HMAC 签名错误 → 401（enforce/dual 同拒——带签名头就必须验过）")
    void hmac_wrongSignature_rejected() throws ServletException, IOException {
        String ts = String.valueOf(System.currentTimeMillis());
        MockHttpServletRequest req = request(CALLBACK_PATH, BODY);
        req.addHeader(RuntimeCallbackSecurityFilter.TIMESTAMP_HEADER, ts);
        req.addHeader(RuntimeCallbackSecurityFilter.SIGNATURE_HEADER, sign("wrong-key", ts, BODY));
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        dualFilter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    @DisplayName("时间戳超 ±300s（重放窗口外）→ 401：封重放")
    void hmac_staleTimestamp_rejected() throws ServletException, IOException {
        String staleTs = String.valueOf(System.currentTimeMillis() - 300_001L);
        MockHttpServletRequest req = request(CALLBACK_PATH, BODY);
        req.addHeader(RuntimeCallbackSecurityFilter.TIMESTAMP_HEADER, staleTs);
        req.addHeader(RuntimeCallbackSecurityFilter.SIGNATURE_HEADER, sign(TOKEN, staleTs, BODY));
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        dualFilter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    @DisplayName("时间戳非法（非数字）→ 401")
    void hmac_malformedTimestamp_rejected() throws ServletException, IOException {
        MockHttpServletRequest req = request(CALLBACK_PATH, BODY);
        req.addHeader(RuntimeCallbackSecurityFilter.TIMESTAMP_HEADER, "not-a-number");
        req.addHeader(RuntimeCallbackSecurityFilter.SIGNATURE_HEADER, "deadbeef");
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        dualFilter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("签名对但请求体被篡改（改 1 字节）→ 401")
    void hmac_tamperedBody_rejected() throws ServletException, IOException {
        String ts = String.valueOf(System.currentTimeMillis());
        // 对原 body 签名，但发送篡改后的 body
        MockHttpServletRequest req = request(CALLBACK_PATH, BODY.replace("SKILL", "ADMIN"));
        req.addHeader(RuntimeCallbackSecurityFilter.TIMESTAMP_HEADER, ts);
        req.addHeader(RuntimeCallbackSecurityFilter.SIGNATURE_HEADER, sign(TOKEN, ts, BODY));
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        dualFilter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("无签名头 + 静态 token 正确 + DUAL → 放行（旧 sidecar 发布兼容期）")
    void legacy_staticToken_dualMode_passes() throws ServletException, IOException {
        MockHttpServletRequest req = request(CALLBACK_PATH, BODY);
        req.addHeader(RuntimeCallbackSecurityFilter.TOKEN_HEADER, TOKEN);
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        dualFilter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    @DisplayName("无签名头 + ENFORCE → 401（sidecar 全量升级后收紧）")
    void legacy_missingSignature_enforceMode_rejected() throws ServletException, IOException {
        MockHttpServletRequest req = request(CALLBACK_PATH, BODY);
        req.addHeader(RuntimeCallbackSecurityFilter.TOKEN_HEADER, TOKEN);
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        enforceFilter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    @DisplayName("无签名头 + 静态 token 错误 + DUAL → 401")
    void legacy_wrongStaticToken_rejected() throws ServletException, IOException {
        MockHttpServletRequest req = request(CALLBACK_PATH, BODY);
        req.addHeader(RuntimeCallbackSecurityFilter.TOKEN_HEADER, "wrong");
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        dualFilter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("共享密钥未配置 → fail-closed（静态/HMAC 双路全拒）")
    void tokenBlank_failClosed() throws ServletException, IOException {
        RuntimeCallbackSecurityFilter unconfigured =
                new RuntimeCallbackSecurityFilter("", () -> "DUAL", null);
        MockHttpServletRequest legacyReq = request(CALLBACK_PATH, BODY);
        legacyReq.addHeader(RuntimeCallbackSecurityFilter.TOKEN_HEADER, "");
        MockHttpServletResponse res = new MockHttpServletResponse();
        unconfigured.doFilter(legacyReq, res, new MockFilterChain());
        assertThat(res.getStatus()).isEqualTo(401);

        String ts = String.valueOf(System.currentTimeMillis());
        MockHttpServletRequest hmacReq = request(CALLBACK_PATH, BODY);
        hmacReq.addHeader(RuntimeCallbackSecurityFilter.TIMESTAMP_HEADER, ts);
        // 签名用非空 key 计算（空 key 无法构造 SecretKeySpec）——过滤器在验签前先判空密钥 fail-closed
        hmacReq.addHeader(RuntimeCallbackSecurityFilter.SIGNATURE_HEADER, sign(TOKEN, ts, BODY));
        MockHttpServletResponse res2 = new MockHttpServletResponse();
        unconfigured.doFilter(hmacReq, res2, new MockFilterChain());
        assertThat(res2.getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("非回调路径 → 直通无校验")
    void nonCallbackPath_passesThrough() throws ServletException, IOException {
        MockHttpServletRequest req = request("/api/chat/messages", BODY);
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        dualFilter.doFilter(req, res, chain);

        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    @DisplayName("HMAC 放行时下游可完整重读请求体（BodyCachingRequest 重放）")
    void hmac_pass_bodyReReadableDownstream() throws ServletException, IOException {
        String ts = String.valueOf(System.currentTimeMillis());
        MockHttpServletRequest req = request(CALLBACK_PATH, BODY);
        req.addHeader(RuntimeCallbackSecurityFilter.TIMESTAMP_HEADER, ts);
        req.addHeader(RuntimeCallbackSecurityFilter.SIGNATURE_HEADER, sign(TOKEN, ts, BODY));

        // 下游模拟：一个读 body 的 FilterChain
        final String[] downstreamBody = {null};
        MockFilterChain chain = new MockFilterChain(new jakarta.servlet.http.HttpServlet() {
            @Override
            protected void doPost(jakarta.servlet.http.HttpServletRequest r,
                                  jakarta.servlet.http.HttpServletResponse resp) throws IOException {
                downstreamBody[0] = new String(r.getInputStream().readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8);
            }
        });

        dualFilter.doFilter(req, new MockHttpServletResponse(), chain);

        assertThat(downstreamBody[0]).isEqualTo(BODY);
    }

    @Test
    @DisplayName("hmacSha256Hex：RFC 知名向量对拍（HMAC-SHA256 自检）")
    void hmacSha256_knownVector() {
        // RFC 4231 Test Case 2：key="Jefe"，data="what do ya want for nothing?"
        assertThat(RuntimeCallbackSecurityFilter.hmacSha256Hex("Jefe", "what do ya want for nothing?"))
                .isEqualTo("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843");
    }
}
