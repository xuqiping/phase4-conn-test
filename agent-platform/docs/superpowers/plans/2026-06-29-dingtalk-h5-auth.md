# 钉钉 H5 微应用免登接入 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让用户在钉钉手机端 / PC 端打开 H5 微应用时免登进入本平台，由钉钉授权码换取本平台 JWT，复用现有认证体系与全部业务功能。

**Architecture:** 钉钉新版 OAuth 免登流程。前端检测钉钉容器 → 重定向到钉钉授权页拿 `authCode` → 回到前端回调页 → POST `authCode` 到后端 → 后端 `DingTalkService` 用 `authCode` 换用户 `accessToken`，再调 `/contact/users/me` 拿 `unionId/nick/avatar` → 按 `unionId` 查找或自动建本平台用户（无密码，`bind_type=dingtalk`）→ `AuthService` 签发标准 JWT（access+refresh）→ 前端存 token 进现有 `stores/auth.ts`，之后所有请求走现有 Axios 拦截器。

**Tech Stack:** Spring Boot 3.2.5 / Java 17 / Spring WebFlux `WebClient`（pom 已有 `spring-boot-starter-webflux`）/ MyBatis-Plus / Flyway / JJWT / OkHttp `MockWebServer`（test，pom 已有）/ Vue 3 + TS + Pinia。

## Global Constraints

- 包名统一 `com.superprogrammer.auth.dingtalk.*`（新增子包）。
- 所有实体继承 `BaseEntity`（含 `id/createdBy/createdAt/updatedBy/updatedAt/deleted/version`）。
- 响应统一 `R<T>`；业务异常抛 `BusinessException(ErrorCode)`。
- 数据库 PostgreSQL，迁移文件 `src/main/resources/db/migration/V{n}__xxx.sql`，下一个版本号 **V41**（当前最新 V40）。
- 逻辑删除 `deleted` + `@TableLogic`；自动填充 `created_by/at/updated_by/at`。
- 前端：Naive UI 暗色主题，API 走 `src/api/request.ts`（Axios 自动注入 Bearer + 401 刷新），token 持久化走 `src/utils/storage.ts`。
- 钉钉密钥（appKey/appSecret）入 `application.yml`（生产用环境变量覆盖），不入库。
- 用钉钉**新版 API**（`api.dingtalk.com`，2021+），不用旧版 `oapi.dingtalk.com/topapi/v2/user/getuserinfoByCode`。
- `unionId` 作为跨应用稳定标识绑定用户；`openId` 仅记录。

---

## File Structure

**新建（后端）：**
- `backend/src/main/java/com/superprogrammer/auth/dingtalk/config/DingTalkProperties.java` — `@ConfigurationProperties(prefix="dingtalk")`，持有 appKey/appSecret/agentId。
- `backend/src/main/java/com/superprogrammer/auth/dingtalk/service/DingTalkService.java` — 调钉钉 API：换 userAccessToken、拉用户信息；内部维护 WebClient。
- `backend/src/main/java/com/superprogrammer/auth/dingtalk/dto/DingTalkLoginRequest.java` — `{ authCode }`。
- `backend/src/main/java/com/superprogrammer/auth/dingtalk/controller/DingTalkAuthController.java` — `POST /api/auth/dingtalk/login`。

**新建（前端）：**
- `frontend/src/utils/dingtalk.ts` — UA 判定 + 授权重定向拼装。
- `frontend/src/views/DingTalkCallbackView.vue` — 回调页，读 `authCode` → POST 后端 → 落地。

**修改：**
- `backend/src/main/resources/db/migration/V41__add_dingtalk_user_binding.sql`（新建）+ `User.java` 加字段。
- `AuthService.java` 加 `loginByDingTalk(User)` 方法 + 抽取发 token 公共逻辑。
- `SecurityConfig.java` 白名单加 `/api/auth/dingtalk/login`。
- `application.yml` 加 `dingtalk:` 配置块。
- `frontend/src/api/auth.ts` 加 `dingTalkLogin(authCode)`。
- `frontend/src/stores/auth.ts` 加 `loginByDingTalk(authCode)` action。
- `frontend/src/router/index.ts` 注册 `/dingtalk/callback` 路由（白名单，免登录）。
- `项目工程文档/项目功能介绍/速查表/01-认证与登录.md` 增钉钉章节。

---

### Task 1: 用户表加钉钉绑定字段

**Files:**
- Create: `backend/src/main/resources/db/migration/V41__add_dingtalk_user_binding.sql`
- Modify: `backend/src/main/java/com/superprogrammer/auth/entity/User.java`
- Test: 启动时 Flyway 迁移自动执行；用既有 `mvn test` 冒烟（H2 不跑迁移，故此任务靠 Task 4/5 的集成断言覆盖，本任务只保证 SQL 语法 + 实体字段对齐）。

**Interfaces:**
- Consumes: 现有 `users` 表（`username/password/email/avatar/status/last_login_at` + `BaseEntity` 字段）。
- Produces: `User.dingtalkUnionId`、`User.dingtalkOpenId`、`User.bindType` 字段；DB 列 `dingtalk_union_id`（唯一部分索引，允许多 NULL）。

- [ ] **Step 1: 写 V41 迁移文件**

```sql
-- 钉钉 H5 微应用免登：用户绑定字段
-- bind_type 区分账密用户(password)与钉钉免登用户(dingtalk)
ALTER TABLE users ADD COLUMN IF NOT EXISTS bind_type        VARCHAR(20)  NOT NULL DEFAULT 'password';
ALTER TABLE users ADD COLUMN IF NOT EXISTS dingtalk_union_id VARCHAR(64);
ALTER TABLE users ADD COLUMN IF NOT EXISTS dingtalk_open_id  VARCHAR(64);

-- unionId 唯一，但允许多个 NULL（账密用户未绑定时为 NULL）。用部分索引。
CREATE UNIQUE INDEX IF NOT EXISTS uk_users_dingtalk_union_id
    ON users (dingtalk_union_id)
    WHERE dingtalk_union_id IS NOT NULL;
```

- [ ] **Step 2: 改 `User.java`，加三个字段**

在 `lastLoginAt` 下方追加（保持现有字段不动）：

```java
    /** 登录方式：password=账密，dingtalk=钉钉免登 */
    private String bindType;

    /** 钉钉 unionId（跨应用稳定标识，账密用户为 null） */
    private String dingtalkUnionId;

    /** 钉钉 openId（应用内标识） */
    private String dingtalkOpenId;
```

- [ ] **Step 3: 校验编译**

Run: `cd backend && mvn -q compile`
Expected: BUILD SUCCESS。

- [ ] **Step 4: 提交**

```bash
git add backend/src/main/resources/db/migration/V41__add_dingtalk_user_binding.sql backend/src/main/java/com/superprogrammer/auth/entity/User.java
git commit -m "feat(auth): V41 users 表加钉钉绑定字段(bind_type/union_id/open_id)"
```

---

### Task 2: 钉钉配置属性

**Files:**
- Create: `backend/src/main/java/com/superprogrammer/auth/dingtalk/config/DingTalkProperties.java`
- Modify: `backend/src/main/resources/application.yml`

**Interfaces:**
- Consumes: 无。
- Produces: `DingTalkProperties` Bean（`getAppKey()/getAppSecret()/getAgentId()/getEnabled()`），供 Task 3 `DingTalkService` 注入。

- [ ] **Step 1: 写 `DingTalkProperties.java`**

```java
package com.superprogrammer.auth.dingtalk.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "dingtalk")
public class DingTalkProperties {

    /** 是否启用钉钉免登（默认关，配齐密钥再开） */
    private boolean enabled = false;

    /** H5 微应用 AppKey（= OAuth client_id） */
    private String appKey;

    /** H5 微应用 AppSecret */
    private String appSecret;

    /** 微应用 AgentId（记录用，OAuth 免登流程不强制） */
    private String agentId;
}
```

- [ ] **Step 2: 在 `application.yml` 追加配置块**（贴到文件末尾，缩进对齐已有顶层 key）

```yaml
dingtalk:
  enabled: ${DINGTALK_ENABLED:false}
  app-key: ${DINGTALK_APP_KEY:}
  app-secret: ${DINGTALK_APP_SECRET:}
  agent-id: ${DINGTALK_AGENT_ID:}
```

- [ ] **Step 3: 编译校验**

Run: `cd backend && mvn -q compile`
Expected: BUILD SUCCESS。

- [ ] **Step 4: 提交**

```bash
git add backend/src/main/java/com/superprogrammer/auth/dingtalk/config/DingTalkProperties.java backend/src/main/resources/application.yml
git commit -m "feat(auth): 钉钉免登配置属性 DingTalkProperties"
```

---

### Task 3: DingTalkService — 换 userAccessToken + 拉用户信息

**Files:**
- Create: `backend/src/main/java/com/superprogrammer/auth/dingtalk/service/DingTalkService.java`
- Test: `backend/src/test/java/com/superprogrammer/auth/dingtalk/service/DingTalkServiceTest.java`

**Interfaces:**
- Consumes: `DingTalkProperties`（Task 2）。
- Produces: `DingTalkUserInfo exchangeUser(String authCode)` —— 入参钉钉 `authCode`，出参含 `unionId/openId/nick/avatar`。供 Task 4 `AuthService` 用。

`DingTalkUserInfo` 作为 `DingTalkService` 内部 public record：

```java
public record DingTalkUserInfo(String unionId, String openId, String nick, String avatar) {}
```

钉钉新版 API（base host `https://api.dingtalk.com`）：
1. `POST /v1.0/oauth2/userAccessToken`，body `{clientId, clientSecret, code, grantType:"authorization_code"}` → 响应 `{accessToken, expireIn}`。
2. `GET /v1.0/contact/users/me`，header `x-acs-dingtalk-access-token: {accessToken}` → 响应 `{openId, unionId, nick, avatarUrl}`。

- [ ] **Step 1: 写失败测试 `DingTalkServiceTest`**

用 OkHttp `MockWebServer` 把两条钉钉 API 桩掉。`baseUrl` 通过反射注入（或加 package-private setter）。

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
        // 测试钩子：把线上 https://api.dingtalk.com 换成本地 MockWebServer
        service.setApiBase(base);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    @DisplayName("authCode 换 userAccessToken 再拉用户信息，聚合返回 unionId/openId/nick/avatar")
    void exchangeUser_success() throws InterruptedException {
        // 1) userAccessToken 响应
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"accessToken\":\"uat-123\",\"expireIn\":7200}"));
        // 2) /contact/users/me 响应
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"openId\":\"oid-1\",\"unionId\":\"uid-1\",\"nick\":\"张三\",\"avatarUrl\":\"https://x/a.png\"}"));

        DingTalkService.DingTalkUserInfo info = service.exchangeUser("auth-code-abc");

        assertThat(info.unionId()).isEqualTo("uid-1");
        assertThat(info.openId()).isEqualTo("oid-1");
        assertThat(info.nick()).isEqualTo("张三");
        assertThat(info.avatar()).isEqualTo("https://x/a.png");

        // 断言换 token 请求体含 clientId/clientSecret/code/grantType
        RecordedRequest tokenReq = server.takeRequest();
        assertThat(tokenReq.getPath()).isEqualTo("/v1.0/oauth2/userAccessToken");
        String body = tokenReq.getBody().readUtf8();
        assertThat(body).contains("\"clientId\":\"app-key-xxx\"");
        assertThat(body).contains("\"clientSecret\":\"app-secret-yyy\"");
        assertThat(body).contains("\"code\":\"auth-code-abc\"");
        assertThat(body).contains("\"grantType\":\"authorization_code\"");

        // 断言拉用户信息带对 header
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
Expected: PASS，2 个用例全绿。

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/com/superprogrammer/auth/dingtalk/service/DingTalkService.java backend/src/test/java/com/superprogrammer/auth/dingtalk/service/DingTalkServiceTest.java
git commit -m "feat(auth): DingTalkService 换 userAccessToken+拉用户信息(MockWebServer 测试)"
```

---

### Task 4: AuthService 增 loginByDingTalk

**Files:**
- Modify: `backend/src/main/java/com/superprogrammer/auth/service/AuthService.java`
- Test: `backend/src/test/java/com/superprogrammer/auth/service/AuthServiceDingTalkTest.java`

**Interfaces:**
- Consumes: `DingTalkService.DingTalkUserInfo`（Task 3），`User.dingtalkUnionId/dingtalkOpenId/bindType`（Task 1），`JwtUtil`、`UserMapper`、`RoleMapper`、`UserRoleMapper`（既有）。
- Produces: `AuthService.loginByDingTalk(DingTalkUserInfo info)` → `TokenResponse`（与账密登录同结构）。供 Task 5 Controller 调。

签名：

```java
public TokenResponse loginByDingTalk(DingTalkService.DingTalkUserInfo info);
```

- [ ] **Step 1: 写失败测试 `AuthServiceDingTalkTest`**

```java
package com.superprogrammer.auth.service;

import com.superprogrammer.auth.dingtalk.service.DingTalkService;
import com.superprogrammer.auth.dto.TokenResponse;
import com.superprogrammer.auth.entity.Role;
import com.superprogrammer.auth.entity.User;
import com.superprogrammer.auth.mapper.RoleMapper;
import com.superprogrammer.auth.mapper.UserMapper;
import com.superprogrammer.auth.mapper.UserRoleMapper;
import com.superprogrammer.auth.security.JwtUtil;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.system.service.SystemSettingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceDingTalkTest {

    @Mock UserMapper userMapper;
    @Mock UserRoleMapper userRoleMapper;
    @Mock RoleMapper roleMapper;
    @Mock JwtUtil jwtUtil;
    @Mock PasswordEncoder passwordEncoder;
    @Mock StringRedisTemplate redisTemplate;
    @Mock SystemSettingService systemSettingService;
    @Mock ValueOperations<String, String> valueOps;

    @InjectMocks AuthService authService;

    @BeforeEach
    void init() {
        lenient().when(systemSettingService.getAccessTokenExpirationMs()).thenReturn(900000L);
    }

    @Test
    @DisplayName("unionId 已存在 → 直接登录，不重复建号，签 JWT")
    void loginByDingTalk_existingUser() {
        DingTalkService.DingTalkUserInfo info =
                new DingTalkService.DingTalkUserInfo("uid-1", "oid-1", "张三", "https://x/a.png");
        User exist = new User();
        exist.setId(7L);
        exist.setUsername("dt_uid-1");
        exist.setDingtalkUnionId("uid-1");
        when(userMapper.selectOne(any())).thenReturn(exist);
        when(userMapper.selectRoleCodesByUsername(anyString())).thenReturn(java.util.List.of("user"));
        when(jwtUtil.generateAccessToken(eq(7L), anyString(), any(), anyLong())).thenReturn("access");
        when(jwtUtil.generateRefreshToken(7L)).thenReturn("refresh");

        TokenResponse resp = authService.loginByDingTalk(info);

        assertThat(resp.getAccessToken()).isEqualTo("access");
        assertThat(resp.getRefreshToken()).isEqualTo("refresh");
        verify(userMapper, never()).insert(any(User.class));
    }

    @Test
    @DisplayName("unionId 不存在 → 自动建号(bind_type=dingtalk)，分配 user 角色，签 JWT")
    void loginByDingTalk_newUser() {
        DingTalkService.DingTalkUserInfo info =
                new DingTalkService.DingTalkUserInfo("uid-2", "oid-2", "李四", null);
        when(userMapper.selectOne(any())).thenReturn(null);
        // insert 回填 id
        doAnswer(inv -> { ((User) inv.getArgument(0)).setId(9L); return 1; })
                .when(userMapper).insert(any(User.class));
        Role role = new Role(); role.setId(2L); role.setCode("user");
        when(roleMapper.selectOne(any())).thenReturn(role);
        when(userMapper.selectRoleCodesByUsername(anyString())).thenReturn(java.util.List.of("user"));
        when(jwtUtil.generateAccessToken(eq(9L), anyString(), any(), anyLong())).thenReturn("access");
        when(jwtUtil.generateRefreshToken(9L)).thenReturn("refresh");

        TokenResponse resp = authService.loginByDingTalk(info);

        assertThat(resp.getAccessToken()).isEqualTo("access");
        verify(userMapper).insert(argThat(u ->
                "dingtalk".equals(u.getBindType())
                && "uid-2".equals(u.getDingtalkUnionId())
                && "ACTIVE".equals(u.getStatus())));
        verify(userRoleMapper).insert(any());
    }

    @Test
    @DisplayName("unionId 为空 → 抛 BusinessException")
    void loginByDingTalk_emptyUnionId() {
        DingTalkService.DingTalkUserInfo info =
                new DingTalkService.DingTalkUserInfo("", "oid", "nick", null);
        assertThatThrownBy(() -> authService.loginByDingTalk(info))
                .isInstanceOf(BusinessException.class);
    }

    // eq 辅助：Mockito 自带 eq，但带 Long/Object 重载需显式导入；此处补一个本地 eq 占位避免编译歧义
    private static long eq(long v) { return v; }
}
```

> 注：上面 `jwtUtil.generateAccessToken(eq(7L), ...)` 中的 `eq` 用 `org.mockito.ArgumentMatchers.eq`（已在 import 中）。本地 `eq` 仅用于 `anyLong()` 不便处，按编译报错保留或删除——优先删本地 `eq`，全部走 `anyLong()`/`eq()`。如下 Step 3 实现配合。

- [ ] **Step 2: 运行测试确认失败**

Run: `cd backend && mvn -q -Dtest=AuthServiceDingTalkTest test`
Expected: FAIL —— `loginByDingTalk` 方法不存在，编译错误。

- [ ] **Step 3: 改 `AuthService.java`**

3a. 顶部加 import（紧接现有 import 块尾部）：

```java
import com.superprogrammer.auth.dingtalk.service.DingTalkService;
```

3b. 抽取发 token 公共方法（重构现有 `login` 的发 token 段）。在 `login` 方法 return 之前，把发 token 抽成私有方法（DRY）。在类内任意方法之间加：

```java
    /**
     * 公共发 token：根据已认证的 User 签发 access+refresh，返回 TokenResponse。
     */
    private TokenResponse issueTokens(User user, List<String> roleCodes, List<String> permissionCodes) {
        long accessExpirationMs = systemSettingService.getAccessTokenExpirationMs();
        String accessToken = jwtUtil.generateAccessToken(user.getId(), user.getUsername(), roleCodes, accessExpirationMs);
        String refreshToken = jwtUtil.generateRefreshToken(user.getId());

        user.setLastLoginAt(OffsetDateTime.now());
        userMapper.updateById(user);

        return TokenResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .tokenType("Bearer")
                .expiresIn(accessExpirationMs)
                .userInfo(TokenResponse.UserInfo.builder()
                        .id(user.getId())
                        .username(user.getUsername())
                        .email(user.getEmail())
                        .avatar(user.getAvatar())
                        .roles(roleCodes)
                        .permissions(permissionCodes)
                        .build())
                .build();
    }
```

3c. 把 `login` 方法末尾的 `// 生成JWT Token ... return ... .build();` 整段替换为：

```java
        // 生成JWT Token（走公共方法）
        return issueTokens(user, roleCodes, permissionCodes);
```

（`roleCodes`/`permissionCodes` 在 `login` 中已查好，复用；删掉原末尾的 builder 块。）

3d. 加钉钉登录方法（贴在 `login` 方法之后）：

```java
    /**
     * 钉钉免登登录：按 unionId 查找或自动建号，签发本平台 JWT。
     */
    @Transactional
    public TokenResponse loginByDingTalk(DingTalkService.DingTalkUserInfo info) {
        if (info == null || info.unionId() == null || info.unionId().isBlank()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "钉钉 unionId 为空");
        }

        // 1) 按 unionId 查既有用户
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getDingtalkUnionId, info.unionId());
        User user = userMapper.selectOne(wrapper);

        if (user == null) {
            // 2) 首次免登 → 自动建号
            user = new User();
            user.setUsername("dt_" + info.unionId());
            user.setBindType("dingtalk");
            user.setDingtalkUnionId(info.unionId());
            user.setDingtalkOpenId(info.openId());
            user.setAvatar(info.avatar());
            user.setStatus("ACTIVE");
            userMapper.insert(user);

            // 分配默认角色 user
            LambdaQueryWrapper<Role> roleWrapper = new LambdaQueryWrapper<>();
            roleWrapper.eq(Role::getCode, "user");
            Role defaultRole = roleMapper.selectOne(roleWrapper);
            if (defaultRole != null) {
                userRoleMapper.insert(new UserRole(user.getId(), defaultRole.getId()));
            }
            log.info("钉钉用户首次登录自动建号: unionId={}, userId={}", info.unionId(), user.getId());
        } else {
            // 已绑定 → 顺带刷新 openId/avatar（防钉钉头像变更）
            boolean dirty = false;
            if (info.openId() != null && !info.openId().equals(user.getDingtalkOpenId())) {
                user.setDingtalkOpenId(info.openId()); dirty = true;
            }
            if (info.avatar() != null && !info.avatar().equals(user.getAvatar())) {
                user.setAvatar(info.avatar()); dirty = true;
            }
            if (!"ACTIVE".equals(user.getStatus())) {
                throw new BusinessException(ErrorCode.UNAUTHORIZED, "用户已被禁用或锁定");
            }
            if (!dirty) {
                // 不走 issueTokens 内的 updateById 也不会改 unionId；issueTokens 会刷 lastLoginAt
            }
        }

        List<String> roleCodes = userMapper.selectRoleCodesByUsername(user.getUsername());
        List<String> permissionCodes = userMapper.selectPermissionCodesByUserId(user.getId());
        return issueTokens(user, roleCodes, permissionCodes);
    }
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd backend && mvn -q -Dtest=AuthServiceDingTalkTest test`
Expected: PASS，3 用例全绿。同时跑既有 Auth 测试确认 `login` 重构没回归：

Run: `cd backend && mvn -q -Dtest='AuthService*' test`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/com/superprogrammer/auth/service/AuthService.java backend/src/test/java/com/superprogrammer/auth/service/AuthServiceDingTalkTest.java
git commit -m "feat(auth): AuthService.loginByDingTalk 按 unionId 查找/建号并签 JWT"
```

---

### Task 5: DingTalkAuthController + 白名单 + DTO

**Files:**
- Create: `backend/src/main/java/com/superprogrammer/auth/dingtalk/dto/DingTalkLoginRequest.java`
- Create: `backend/src/main/java/com/superprogrammer/auth/dingtalk/controller/DingTalkAuthController.java`
- Modify: `backend/src/main/java/com/superprogrammer/auth/security/SecurityConfig.java`
- Test: `backend/src/test/java/com/superprogrammer/auth/dingtalk/controller/DingTalkAuthControllerTest.java`

**Interfaces:**
- Consumes: `AuthService.loginByDingTalk`（Task 4）、`DingTalkService.exchangeUser`（Task 3）。
- Produces: HTTP `POST /api/auth/login/dingtalk`（放白名单），入参 `{authCode}`，出参与账密登录同 `R<TokenResponse>`。

> 路由用 `/api/auth/login/dingtalk`（不碰 `/api/auth/dingtalk/login`，保持 login 前缀聚合）。

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

用 `@WebMvcTest` + `@MockBean`，只验编排（authCode → exchangeUser → loginByDingTalk → 200）。

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

import static org.mockito.ArgumentMatchers.any;
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

---

### Task 6: 前端 UA 判定 + 授权重定向 + API 方法

**Files:**
- Create: `frontend/src/utils/dingtalk.ts`
- Modify: `frontend/src/api/auth.ts`

**Interfaces:**
- Consumes: 环境变量 `VITE_DINGTALK_APP_KEY`（.env 配，见 Step 1）、`VITE_DINGTALK_REDIRECT_URI`。
- Produces: `isDingTalkClient()`、`redirectToDingTalkAuth(state)`（util），`authApi.dingTalkLogin(authCode)`（api）。

钉钉 H5 授权页（新版 OAuth，client_id = AppKey）：

```
https://login.dingtalk.com/oauth2/auth?redirect_uri={REDIRECT_URI}&response_type=code&client_id={APP_KEY}&scope=openid&state={STATE}&prompt=consent
```

授权后钉钉带 `?authCode=xxx&state=yyy` 重定向回 `REDIRECT_URI`。

- [ ] **Step 1: 前端环境变量**

`frontend/.env`（或 `.env.development`）加：

```
VITE_DINGTALK_APP_KEY=dingXXXXXXXX
VITE_DINGTALK_REDIRECT_URI=https://your-domain/dingtalk/callback
```

- [ ] **Step 2: 写 `frontend/src/utils/dingtalk.ts`**

```ts
// ============================================================
// 钉钉 H5 免登工具：UA 判定 + 授权重定向
// ============================================================

const APP_KEY = import.meta.env.VITE_DINGTALK_APP_KEY as string | undefined
const REDIRECT_URI = import.meta.env.VITE_DINGTALK_REDIRECT_URI as string | undefined

/** 当前是否运行在钉钉客户端 webview 内 */
export function isDingTalkClient(): boolean {
  if (typeof navigator === 'undefined') return false
  return /DingTalk/i.test(navigator.userAgent)
}

/** 钉钉免登是否已配置可用 */
export function isDingTalkEnabled(): boolean {
  return !!APP_KEY && !!REDIRECT_URI
}

/**
 * 跳转钉钉授权页。授权后钉钉带 authCode 回到 REDIRECT_URI。
 * @param state 透传状态，防 CSRF（回调页校验）
 */
export function redirectToDingTalkAuth(state = 'dt'): void {
  if (!isDingTalkEnabled()) {
    throw new Error('钉钉免登未配置 VITE_DINGTALK_APP_KEY / VITE_DINGTALK_REDIRECT_URI')
  }
  const params = new URLSearchParams({
    redirect_uri: REDIRECT_URI!,
    response_type: 'code',
    client_id: APP_KEY!,
    scope: 'openid',
    state,
    prompt: 'consent'
  })
  window.location.href = `https://login.dingtalk.com/oauth2/auth?${params.toString()}`
}
```

- [ ] **Step 3: `api/auth.ts` 加方法**

在 `authApi` 对象内 `logout` 之后、`getMe` 之前加：

```ts
  /**
   * 钉钉免登登录
   * POST /api/auth/login/dingtalk
   */
  dingTalkLogin(authCode: string) {
    return request.post<ApiResponse<LoginResponse>>('/auth/login/dingtalk', { authCode })
  },
```

- [ ] **Step 4: 类型检查**

Run: `cd frontend && npm run type-check`（或 `npx vue-tsc --noEmit`）
Expected: 无报错。

- [ ] **Step 5: 提交**

```bash
git add frontend/src/utils/dingtalk.ts frontend/src/api/auth.ts frontend/.env
git commit -m "feat(frontend): 钉钉 UA 判定+授权重定向 util 与 dingTalkLogin API"
```

---

### Task 7: 前端回调页 + Store action + 路由

**Files:**
- Modify: `frontend/src/stores/auth.ts`
- Create: `frontend/src/views/DingTalkCallbackView.vue`
- Modify: `frontend/src/router/index.ts`
- Modify: `frontend/src/views/LoginView.vue`（加「钉钉登录」入口按钮）

**Interfaces:**
- Consumes: `redirectToDingTalkAuth/isDingTalkClient`（Task 6）、`authApi.dingTalkLogin`（Task 6）、`STORAGE_KEYS/setStorage`（既有）。
- Produces: 路由 `/dingtalk/callback`（免登录白名单）；`authStore.loginByDingTalk(authCode)` action；LoginView 上的钉钉登录入口。

- [ ] **Step 1: `stores/auth.ts` 加 action**

在 `refreshAccessToken` 方法之后加：

```ts
  /**
   * 钉钉免登：用 authCode 换 token
   */
  async function loginByDingTalk(authCode: string) {
    loading.value = true
    try {
      const res = await authApi.dingTalkLogin(authCode)
      const { accessToken: at, refreshToken: rt, userInfo: info } = res.data.data
      accessToken.value = at
      refreshToken.value = rt
      userInfo.value = info
      setStorage(STORAGE_KEYS.ACCESS_TOKEN, at)
      setStorage(STORAGE_KEYS.REFRESH_TOKEN, rt)
      setStorage(STORAGE_KEYS.USER_INFO, info)
    } finally {
      loading.value = false
    }
  }
```

并在 `return { ... }` 内加 `loginByDingTalk,`（紧跟 `refreshAccessToken,` 后）。

- [ ] **Step 2: 写回调页 `views/DingTalkCallbackView.vue`**

```vue
<template>
  <div class="dt-callback">
    <n-spin size="large" description="钉钉登录中..." />
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useMessage } from 'naive-ui'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const route = useRoute()
const message = useMessage()
const authStore = useAuthStore()
const done = ref(false)

onMounted(async () => {
  const authCode = route.query.authCode as string | undefined
  const state = route.query.state as string | undefined
  if (state && state !== 'dt') {
    message.error('state 校验失败')
    router.replace('/login')
    return
  }
  if (!authCode) {
    message.error('未收到钉钉授权码')
    router.replace('/login')
    return
  }
  try {
    await authStore.loginByDingTalk(authCode)
    router.replace('/')
  } catch (e: any) {
    message.error(e?.message || '钉钉登录失败')
    router.replace('/login')
  } finally {
    done.value = true
  }
})
</script>

<style scoped lang="scss">
.dt-callback {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100vh;
}
</style>
```

- [ ] **Step 3: 路由注册（白名单免登录）**

`router/index.ts`：
- 在路由表加（与 `/login` 同级，`meta.requiresAuth = false`）：

```ts
  {
    path: '/dingtalk/callback',
    name: 'DingTalkCallback',
    component: () => import('@/views/DingTalkCallbackView.vue'),
    meta: { requiresAuth: false, title: '钉钉登录' }
  },
```

- 在登录守卫里，把 `/dingtalk/callback` 与 `/login` 一起放行（既有守卫逻辑里 `meta.requiresAuth === false` 的判断应已覆盖；若守卫用的是路径白名单，则补一条 `name === 'DingTalkCallback'`）。

- [ ] **Step 4: LoginView 加钉钉入口**

`LoginView.vue` 登录卡片底部加按钮（UI 按 Naive UI 暗色主题风格，按钮文案「钉钉登录」）。在现有登录按钮下方加：

```vue
<n-button
  v-if="dtEnabled"
  block
  secondary
  type="primary"
  :loading="false"
  @click="onDingTalkLogin"
>
  钉钉登录
</n-button>
```

`<script setup>` 内加：

```ts
import { isDingTalkEnabled, redirectToDingTalkAuth } from '@/utils/dingtalk'

const dtEnabled = isDingTalkEnabled()

function onDingTalkLogin() {
  redirectToDingTalkAuth('dt')
}
```

（钉钉容器内会自动走回调；非钉钉浏览器点此按钮也能触发钉钉扫码/登录授权。）

- [ ] **Step 5: 类型检查 + 构建**

Run: `cd frontend && npm run type-check && npm run build`
Expected: 无错，构建成功。

- [ ] **Step 6: 提交**

```bash
git add frontend/src/stores/auth.ts frontend/src/views/DingTalkCallbackView.vue frontend/src/views/LoginView.vue frontend/src/router/index.ts
git commit -m "feat(frontend): 钉钉回调页+store action+路由+登录入口"
```

---

### Task 8: 文档 + 钉钉平台配置清单

**Files:**
- Modify: `项目工程文档/项目功能介绍/速查表/01-认证与登录.md`

- [ ] **Step 1: 速查表 01 末尾追加钉钉章节**

在「## 数据表」之前插入：

```markdown
## 钉钉 H5 微应用免登（可选）
- 流程：钉钉容器内打开 H5 → 重定向钉钉授权页拿 `authCode` → 回调页 `/dingtalk/callback` → POST `/api/auth/login/dingtalk` → 后端用 `authCode` 换用户 `unionId` → 按 `unionId` 查/建本地用户 → 签发标准 JWT（与账密登录同）。
- 后端：
  - 控制器：`auth/dingtalk/controller/DingTalkAuthController.java` — `POST /api/auth/login/dingtalk`
  - 服务：`auth/dingtalk/service/DingTalkService.java` — 换 userAccessToken + 拉用户信息
  - 配置：`auth/dingtalk/config/DingTalkProperties.java`（`dingtalk.enabled/app-key/app-secret/agent-id`）
  - 业务：`AuthService.loginByDingTalk()` — unionId 查找/自动建号、签 JWT
  - 白名单：`SecurityConfig` 放行 `/api/auth/login/dingtalk`
- 前端：
  - 工具：`utils/dingtalk.ts` — `isDingTalkClient()` / `redirectToDingTalkAuth()`
  - 回调页：`views/DingTalkCallbackView.vue`（路由 `/dingtalk/callback`，免登录）
  - 登录页：`LoginView.vue` 钉钉登录入口
  - API：`auth.ts` `dingTalkLogin(authCode)` / store `loginByDingTalk(authCode)`
- 数据表：`users` 加 `bind_type / dingtalk_union_id（唯一部分索引）/ dingtalk_open_id`（V41）。
- 钉钉平台配置清单（管理员）：
  1. 钉钉开放平台创建「H5 微应用」（企业内部应用）。
  2. 「开发配置 > 安全设置」：把 `VITE_DINGTALK_REDIRECT_URI`（如 `https://your-domain/dingtalk/callback`）加入**重定向 URL** 白名单。
  3. 拿 `AppKey/AppSecret/AgentId`，填入后端 `dingtalk.app-key/app-secret/agent-id`（生产走环境变量 `DINGTALK_APP_KEY` 等），`dingtalk.enabled=true`。
  4. 前端 `.env` 填 `VITE_DINGTALK_APP_KEY`、`VITE_DINGTALK_REDIRECT_URI`。
  5. 微应用「应用首页地址」指向前端首页（或 `/login`）。
  6. 在钉钉手机端/PC 端打开微应用，验证免登进入。
```

- [ ] **Step 2: 提交**

```bash
git add "项目工程文档/项目功能介绍/速查表/01-认证与登录.md"
git commit -m "docs(auth): 速查表 01 增钉钉 H5 微应用免登章节"
```

---

## Self-Review

1. **Spec coverage：** 免登全链路——DB(Task1)→配置(Task2)→钉钉API(Task3)→建号发token(Task4)→端点(Task5)→前端UA/重定向(Task6)→回调页/路由/入口(Task7)→文档(Task8)。手机端交互 = 现有 H5 页面在钉钉 webview 内带 JWT 运行，无额外改造，已覆盖。
2. **Placeholder scan：** 无 TBD/TODO；每个 code step 给了完整代码。Task4 测试里 `eq` 辅助有歧义处理说明，实现走 `anyLong()`/`eq()`，已标注。
3. **Type consistency：** `DingTalkUserInfo(unionId,openId,nick,avatar)` record 在 Task3/4/5 一致；`User.dingtalkUnionId/dingtalkOpenId/bindType` 在 Task1/4 一致；端点路径 `/api/auth/login/dingtalk` 在 Task5/6 一致；`dingTalkLogin(authCode)` 在 api/store(Task6/7) 一致。

## 风险与边界

- **企业账号依赖：** 联调必须有钉钉企业管理员 + 已审批的 H5 微应用。开发期 Task3 的 MockWebServer 测试不依赖真实账号。
- **unionId 唯一性：** 同一钉钉用户在同一个开发者账号下不同应用的 unionId 相同；若将来接入多个钉钉租户，需扩 `tenant_id` 维度（本计划单租户）。
- **JWT 与现有体系零冲突：** 钉钉用户落 `users` 表后与账密用户等价，`@RequirePermission`、角色、所有业务 API 全部复用。
- **旧账密登录不受影响：** Task4 仅重构 `login` 末尾为 `issueTokens`，行为等价，跑既有 Auth 测试防回归。
