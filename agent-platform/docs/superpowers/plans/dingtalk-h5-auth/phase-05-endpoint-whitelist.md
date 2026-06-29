# Phase 5 — 免登端点 + 白名单

> 总路由：[README.md](README.md) · 上一：[Phase 4](phase-04-auth-service-dingtalk.md) · 下一：[Phase 6](phase-06-frontend-ua-redirect.md)

**Goal：** HTTP 端点 `POST /api/auth/login/dingtalk`，入参 `{authCode}`，出参与账密登录同 `R<TokenResponse>`；放白名单。

**Files:**
- Create: `backend/src/main/java/com/superprogrammer/auth/dingtalk/dto/DingTalkLoginRequest.java`
- Create: `backend/src/main/java/com/superprogrammer/auth/dingtalk/controller/DingTalkAuthController.java`
- Modify: `backend/src/main/java/com/superprogrammer/auth/security/SecurityConfig.java`
- Test: `backend/src/test/java/com/superprogrammer/auth/dingtalk/controller/DingTalkAuthControllerTest.java`

**Interfaces:**
- Consumes: `AuthService.loginByDingTalk`（Phase 4）、`DingTalkService.exchangeUser`（Phase 3）。
- Produces: HTTP `POST /api/auth/login/dingtalk`（放白名单），供 Phase 6 前端调用。

> 路由用 `/api/auth/login/dingtalk`（login 前缀聚合，与 `/api/auth/login` 同根）。

---

- [ ] **Step 1: 写 DTO `DingTalkLoginRequest.java`**

```java
package com.superprogrammer.auth.dingtalk.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class DingTalkLoginRequest {

    /** 钉钉免登授权码 authCode（前端从钉钉授权回调 URL 取） */
    @NotBlank(message = "authCode 不能为空")
    private String authCode;
}
```

- [ ] **Step 2: 写 Controller `DingTalkAuthController.java`**

```java
package com.superprogrammer.auth.dingtalk.controller;

import com.superprogrammer.auth.dingtalk.dto.DingTalkLoginRequest;
import com.superprogrammer.auth.dingtalk.service.DingTalkService;
import com.superprogrammer.auth.dto.TokenResponse;
import com.superprogrammer.auth.service.AuthService;
import com.superprogrammer.common.result.R;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth/login")
@RequiredArgsConstructor
public class DingTalkAuthController {

    private final DingTalkService dingTalkService;
    private final AuthService authService;

    /**
     * 钉钉免登登录：前端把 authCode POST 上来，换本平台 JWT。
     */
    @PostMapping("/dingtalk")
    public ResponseEntity<R<TokenResponse>> loginByDingTalk(@Valid @RequestBody DingTalkLoginRequest request) {
        DingTalkService.DingTalkUserInfo info = dingTalkService.exchangeUser(request.getAuthCode());
        TokenResponse response = authService.loginByDingTalk(info);
        return ResponseEntity.ok(R.ok(response));
    }
}
```

- [ ] **Step 3: 白名单放行**

`SecurityConfig.java` 现有 `.requestMatchers("/api/auth/refresh").permitAll()` 下方加一行：

```java
                        .requestMatchers("/api/auth/login/dingtalk").permitAll()
```

- [ ] **Step 4: 写 Controller 测试 `DingTalkAuthControllerTest.java`**

用 `@WebMvcTest` + `@MockBean`，验编排（authCode → exchangeUser → loginByDingTalk → 200）。

```java
package com.superprogrammer.auth.dingtalk.controller;

import com.superprogrammer.auth.dingtalk.service.DingTalkService;
import com.superprogrammer.auth.dto.TokenResponse;
import com.superprogrammer.auth.security.JwtAuthenticationFilter;
import com.superprogrammer.auth.service.AuthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = DingTalkAuthController.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
                classes = JwtAuthenticationFilter.class))
@AutoConfigureMockMvc(addFilters = false)
class DingTalkAuthControllerTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @MockBean DingTalkService dingTalkService;
    @MockBean AuthService authService;

    @Test
    void loginByDingTalk_ok() throws Exception {
        DingTalkService.DingTalkUserInfo info =
                new DingTalkService.DingTalkUserInfo("uid-1", "oid-1", "nick", null);
        when(dingTalkService.exchangeUser("code-1")).thenReturn(info);
        when(authService.loginByDingTalk(info)).thenReturn(
                TokenResponse.builder().accessToken("at").refreshToken("rt").tokenType("Bearer").expiresIn(900000L).build());

        mvc.perform(post("/api/auth/login/dingtalk")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"authCode\":\"code-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.accessToken").value("at"));
    }

    @Test
    void loginByDingTalk_blankCode_400() throws Exception {
        mvc.perform(post("/api/auth/login/dingtalk")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"authCode\":\"\"}"))
                .andExpect(status().isBadRequest());
    }
}
```

- [ ] **Step 5: 运行测试确认通过**

Run: `cd backend && mvn -q -Dtest=DingTalkAuthControllerTest test`
Expected: PASS，2 用例全绿。

- [ ] **Step 6: 提交**

```bash
git add backend/src/main/java/com/superprogrammer/auth/dingtalk/ backend/src/main/java/com/superprogrammer/auth/security/SecurityConfig.java backend/src/test/java/com/superprogrammer/auth/dingtalk/controller/DingTalkAuthControllerTest.java
git commit -m "feat(auth): 钉钉免登端点 POST /api/auth/login/dingtalk + 白名单"
```

- [ ] **完成后：** 回 [README](README.md) 勾掉 Phase 5，开 Phase 6。
