# Phase 3 — DingTalkService 换码拉用户

> 总路由：[README.md](README.md) · 上一：[Phase 2](phase-02-dingtalk-properties.md) · 下一：[Phase 4](phase-04-auth-service-dingtalk.md)

**Goal：** `DingTalkService.exchangeUser(authCode)` —— 用 authCode 换用户 accessToken，再拉用户基本信息。MockWebServer 桩测。

**Files:**
- Create: `backend/src/main/java/com/superprogrammer/auth/dingtalk/service/DingTalkService.java`
- Test: `backend/src/test/java/com/superprogrammer/auth/dingtalk/service/DingTalkServiceTest.java`

**Interfaces:**
- Consumes: `DingTalkProperties`（Phase 2）。
- Produces: `DingTalkService.exchangeUser(String authCode)` → `DingTalkUserInfo(unionId,openId,nick,avatar)`，供 Phase 4。

钉钉新版 API（base host `https://api.dingtalk.com`）：
1. `POST /v1.0/oauth2/userAccessToken`，body `{clientId, clientSecret, code, grantType:"authorization_code"}` → `{accessToken, expireIn}`。
2. `GET /v1.0/contact/users/me`，header `x-acs-dingtalk-access-token: {accessToken}` → `{openId, unionId, nick, avatarUrl}`。

---

- [ ] **Step 1: 写失败测试 `DingTalkServiceTest`**

用 OkHttp `MockWebServer` 桩两条钉钉 API。`baseUrl` 通过 package-private setter 注入。

```java
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
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd backend && mvn -q -Dtest=DingTalkServiceTest test`
Expected: FAIL —— `DingTalkService` 不存在，编译错误。

- [ ] **Step 3: 写 `DingTalkService.java` 实现**

```java
package com.superprogrammer.auth.dingtalk.service;

import com.superprogrammer.auth.dingtalk.config.DingTalkProperties;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class DingTalkService {

    private final DingTalkProperties properties;
    private final WebClient.Builder webClientBuilder;

    /** 线上地址；测试用 setter 覆盖指向 MockWebServer */
    @Setter
    private String apiBase = "https://api.dingtalk.com";

    public record DingTalkUserInfo(String unionId, String openId, String nick, String avatar) {}

    /**
     * 用 authCode 换用户 accessToken，再拉用户基本信息。
     */
    public DingTalkUserInfo exchangeUser(String authCode) {
        if (!properties.isEnabled()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "钉钉免登未开启");
        }
        String userAccessToken = fetchUserAccessToken(authCode);
        return fetchUserInfo(userAccessToken);
    }

    @SuppressWarnings("unchecked")
    private String fetchUserAccessToken(String authCode) {
        Map<String, Object> body = Map.of(
                "clientId", properties.getAppKey(),
                "clientSecret", properties.getAppSecret(),
                "code", authCode,
                "grantType", "authorization_code"
        );
        Map<String, Object> resp = webClientBuilder.build().post()
                .uri(apiBase + "/v1.0/oauth2/userAccessToken")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .onStatus(s -> s.is4xxClientError() || s.is5xxServerError(),
                        r -> r.bodyToMono(String.class)
                                .flatMap(t -> Mono.error(new BusinessException(ErrorCode.UNAUTHORIZED, "钉钉换 token 失败: " + t))))
                .bodyToMono(Map.class)
                .block();
        String token = resp == null ? null : (String) resp.get("accessToken");
        if (token == null || token.isBlank()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "钉钉 userAccessToken 为空");
        }
        return token;
    }

    @SuppressWarnings("unchecked")
    private DingTalkUserInfo fetchUserInfo(String userAccessToken) {
        Map<String, Object> resp = webClientBuilder.build().get()
                .uri(apiBase + "/v1.0/contact/users/me")
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .header("x-acs-dingtalk-access-token", userAccessToken)
                .retrieve()
                .onStatus(s -> s.is4xxClientError() || s.is5xxServerError(),
                        r -> r.bodyToMono(String.class)
                                .flatMap(t -> Mono.error(new BusinessException(ErrorCode.UNAUTHORIZED, "钉钉拉取用户信息失败: " + t))))
                .bodyToMono(Map.class)
                .block();
        if (resp == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "钉钉用户信息为空");
        }
        String unionId = (String) resp.get("unionId");
        if (unionId == null || unionId.isBlank()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "钉钉 unionId 为空");
        }
        return new DingTalkUserInfo(
                unionId,
                (String) resp.get("openId"),
                (String) resp.get("nick"),
                (String) resp.get("avatarUrl")
        );
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd backend && mvn -q -Dtest=DingTalkServiceTest test`
Expected: PASS，2 用例全绿。

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/com/superprogrammer/auth/dingtalk/service/DingTalkService.java backend/src/test/java/com/superprogrammer/auth/dingtalk/service/DingTalkServiceTest.java
git commit -m "feat(auth): DingTalkService 换 userAccessToken+拉用户信息(MockWebServer 测试)"
```

- [ ] **完成后：** 回 [README](README.md) 勾掉 Phase 3，开 Phase 4。
