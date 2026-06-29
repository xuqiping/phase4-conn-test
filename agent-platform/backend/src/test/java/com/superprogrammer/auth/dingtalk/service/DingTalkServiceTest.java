package com.superprogrammer.auth.dingtalk.service;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.*;
import org.springframework.web.reactive.function.client.WebClient;
import com.superprogrammer.auth.dingtalk.config.DingTalkProperties;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class DingTalkServiceTest {

    private MockWebServer server;
    private DingTalkService service;

    @BeforeEach
    void setup() throws IOException {
        server = new MockWebServer();
        server.start();
        String base = server.url("/").toString().replaceAll("/$", "");

        DingTalkProperties props = new DingTalkProperties();
        props.setEnabled(true);
        props.setAppKey("app-key-xxx");
        props.setAppSecret("app-secret-yyy");

        service = new DingTalkService(props, WebClient.builder());
        service.setApiBase(base); // 把线上 host 换成本地 MockWebServer
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    @DisplayName("authCode 换 userAccessToken 再拉用户信息，聚合返回 unionId/openId/nick/avatar")
    void exchangeUser_success() throws InterruptedException {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"accessToken\":\"uat-123\",\"expireIn\":7200}"));
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"openId\":\"oid-1\",\"unionId\":\"uid-1\",\"nick\":\"张三\",\"avatarUrl\":\"https://x/a.png\"}"));

        DingTalkService.DingTalkUserInfo info = service.exchangeUser("auth-code-abc");

        assertThat(info.unionId()).isEqualTo("uid-1");
        assertThat(info.openId()).isEqualTo("oid-1");
        assertThat(info.nick()).isEqualTo("张三");
        assertThat(info.avatar()).isEqualTo("https://x/a.png");

        RecordedRequest tokenReq = server.takeRequest();
        assertThat(tokenReq.getPath()).isEqualTo("/v1.0/oauth2/userAccessToken");
        String body = tokenReq.getBody().readUtf8();
        assertThat(body).contains("\"clientId\":\"app-key-xxx\"");
        assertThat(body).contains("\"clientSecret\":\"app-secret-yyy\"");
        assertThat(body).contains("\"code\":\"auth-code-abc\"");
        assertThat(body).contains("\"grantType\":\"authorization_code\"");

        RecordedRequest meReq = server.takeRequest();
        assertThat(meReq.getPath()).isEqualTo("/v1.0/contact/users/me");
        assertThat(meReq.getHeader("x-acs-dingtalk-access-token")).isEqualTo("uat-123");
    }

    @Test
    @DisplayName("钉钉换 token 返回空 accessToken → 抛 BusinessException(UNAUTHORIZED)")
    void exchangeUser_emptyToken() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"accessToken\":\"\",\"expireIn\":0}"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.exchangeUser("bad"))
                .isInstanceOf(com.superprogrammer.common.exception.BusinessException.class);
    }
}
