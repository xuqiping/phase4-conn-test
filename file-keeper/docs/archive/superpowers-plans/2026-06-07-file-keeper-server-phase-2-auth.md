# File Keeper Server Phase 2 Auth Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 `file-keeper/server/` 中实现商业授权服务端的验证码注册、客户端登录、管理员登录、用户审核、禁用和启用闭环。

**Architecture:** 阶段 2 继续保持服务端独立 Maven 工程，控制器只负责 HTTP 入参出参，业务规则放在 service，数据库访问集中在 repository，JWT access token 无状态校验，refresh token 和验证码使用可替换 store。生产/开发使用 Redis store，自动化测试使用测试 profile 下的内存 store，避免测试依赖本机 Redis。

**Tech Stack:** Java 17, Spring Boot 3.2.5, Spring Security, Spring MVC, Validation, JdbcTemplate, JJWT, Redis, H2 test profile, JUnit 5, MockMvc, BCrypt.

---

## Scope

本计划只覆盖阶段 2：

- 客户端验证码发送与校验。
- 客户端注册，注册后用户状态为 `pending_review`。
- 客户端登录、access token、refresh token、logout、refresh。
- 管理员登录、管理员 refresh、管理员 logout。
- 管理员查看用户、审核用户、禁用用户、启用用户。
- 禁用用户后撤销该用户全部 refresh token。
- 管理员高风险操作写入 `admin_audit_logs`。

本计划不覆盖：

- 模块授权、设备绑定、匿名试用权益。
- 管理后台 Vue 前端。
- 桌面端登录 UI 或模块门禁。
- 邮箱/短信真实供应商接入。
- 支付、订单、套餐。

---

## Existing Foundation

阶段 1 已存在：

- `file-keeper/server/pom.xml` — Spring Boot Maven 工程。
- `file-keeper/server/src/main/resources/db/migration/V1__create_auth_schema.sql` — 已创建 `users`、`admin_audit_logs` 等核心表。
- `file-keeper/server/src/main/java/com/superprogrammer/common/R.java` — API 响应包装。
- `file-keeper/server/src/main/java/com/superprogrammer/common/ErrorCode.java` — 通用错误码。
- `file-keeper/server/src/main/java/com/superprogrammer/common/BusinessException.java` — 业务异常。
- `file-keeper/server/src/main/java/com/superprogrammer/common/PageResult.java` — 分页响应。
- `file-keeper/server/src/main/java/com/superprogrammer/config/PasswordEncoderConfig.java` — BCrypt `PasswordEncoder` bean。
- `file-keeper/server/src/main/java/com/superprogrammer/bootstrap/SuperAdminInitializer.java` — 超级管理员初始化。
- `file-keeper/server/src/test/resources/application-test.yml` — H2 测试 profile。

执行阶段 2 前先确认阶段 1 测试仍通过：

```bash
JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot" MAVEN_HOME="/c/Users/19536/.local/tools/apache-maven-3.9.11" PATH="/c/Users/19536/.local/tools/apache-maven-3.9.11/bin:/c/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot/bin:$PATH" mvn -f "file-keeper/server/pom.xml" test
```

Expected: `BUILD SUCCESS`，并且阶段 1 的 2 个测试通过。

---

## API Contract

### Client APIs

- `POST /api/client/verification/send`
  - Request: `{ "contactType": "email", "contact": "user@example.com" }`
  - Request: `{ "contactType": "phone", "contact": "13800138000" }`
  - Response: `{ "code": 200, "msg": "success", "data": null }`

- `POST /api/client/verification/check`
  - Request: `{ "contactType": "email", "contact": "user@example.com", "code": "123456" }`
  - Response: `{ "code": 200, "msg": "success", "data": { "verified": true } }`

- `POST /api/client/auth/register`
  - Request: `{ "email": "user@example.com", "password": "Password123!" }`
  - Request: `{ "phone": "13800138000", "password": "Password123!" }`
  - Requires prior successful `/api/client/verification/check` for the same contact.
  - Response user status: `pending_review`.

- `POST /api/client/auth/login`
  - Request: `{ "identifier": "user@example.com", "password": "Password123!" }`
  - Response includes `accessToken`, `refreshToken`, `expiresInSeconds`, and user summary.
  - `pending_review` and `active` users may login.
  - `disabled` users may not login.

- `POST /api/client/auth/refresh`
  - Request: `{ "refreshToken": "opaque-refresh-token" }`
  - Response includes a new access token and the same refresh token.

- `POST /api/client/auth/logout`
  - Request: `{ "refreshToken": "opaque-refresh-token" }`
  - Response: `{ "code": 200, "msg": "success", "data": null }`

### Admin APIs

- `POST /api/admin/auth/login`
  - Request: `{ "identifier": "admin@example.com", "password": "AdminPass123!" }`
  - Only `role = super_admin` and `status = active` can login here.

- `POST /api/admin/auth/refresh`
  - Request: `{ "refreshToken": "opaque-refresh-token" }`
  - Response includes a new admin access token.

- `POST /api/admin/auth/logout`
  - Request: `{ "refreshToken": "opaque-refresh-token" }`

- `GET /api/admin/users?status=pending_review&page=1&size=20`
  - Requires admin bearer token.
  - Response data uses `PageResult<UserSummary>`.

- `GET /api/admin/users/{id}`
  - Requires admin bearer token.

- `POST /api/admin/users/{id}/approve`
  - Requires admin bearer token.
  - Request: `{ "note": "资料确认通过" }`
  - Changes user status from `pending_review` to `active`.
  - Writes audit log action `user.approve`.

- `POST /api/admin/users/{id}/disable`
  - Requires admin bearer token.
  - Request: `{ "note": "授权违规" }`
  - Changes user status to `disabled`.
  - Revokes all refresh tokens for that user.
  - Writes audit log action `user.disable`.

- `POST /api/admin/users/{id}/enable`
  - Requires admin bearer token.
  - Request: `{ "note": "恢复使用" }`
  - Changes user status to `active`.
  - Writes audit log action `user.enable`.

---

## File Structure

### Modify

- `file-keeper/server/pom.xml` — add JJWT dependencies.
- `file-keeper/server/src/main/resources/application.yml` — add auth/JWT/refresh/verification settings.
- `file-keeper/server/src/test/resources/application-test.yml` — add deterministic test auth settings.

### Create production source

- `file-keeper/server/src/main/java/com/superprogrammer/common/GlobalExceptionHandler.java` — convert validation/business/security exceptions to `R`.
- `file-keeper/server/src/main/java/com/superprogrammer/config/AuthProperties.java` — bind `file-keeper.auth.*` settings.
- `file-keeper/server/src/main/java/com/superprogrammer/security/AuthPrincipal.java` — authenticated user snapshot from JWT.
- `file-keeper/server/src/main/java/com/superprogrammer/security/JwtService.java` — access token create/parse.
- `file-keeper/server/src/main/java/com/superprogrammer/security/JwtAuthenticationFilter.java` — bearer token filter.
- `file-keeper/server/src/main/java/com/superprogrammer/security/SecurityConfig.java` — endpoint authorization rules.
- `file-keeper/server/src/main/java/com/superprogrammer/security/RefreshTokenService.java` — opaque refresh token lifecycle.
- `file-keeper/server/src/main/java/com/superprogrammer/security/RefreshTokenStore.java` — refresh token store interface.
- `file-keeper/server/src/main/java/com/superprogrammer/security/RedisRefreshTokenStore.java` — Redis refresh token store.
- `file-keeper/server/src/main/java/com/superprogrammer/user/dto/SendVerificationRequest.java` — verification send request.
- `file-keeper/server/src/main/java/com/superprogrammer/user/dto/CheckVerificationRequest.java` — verification check request.
- `file-keeper/server/src/main/java/com/superprogrammer/user/dto/VerificationCheckResponse.java` — verification check response.
- `file-keeper/server/src/main/java/com/superprogrammer/user/dto/RegisterRequest.java` — client register request.
- `file-keeper/server/src/main/java/com/superprogrammer/user/dto/LoginRequest.java` — client/admin login request.
- `file-keeper/server/src/main/java/com/superprogrammer/user/dto/RefreshTokenRequest.java` — refresh/logout request.
- `file-keeper/server/src/main/java/com/superprogrammer/user/dto/AuthResponse.java` — login/refresh response.
- `file-keeper/server/src/main/java/com/superprogrammer/user/dto/UserSummary.java` — safe user response.
- `file-keeper/server/src/main/java/com/superprogrammer/user/repository/UserRepository.java` — user SQL access.
- `file-keeper/server/src/main/java/com/superprogrammer/user/service/VerificationCodeStore.java` — verification code store interface.
- `file-keeper/server/src/main/java/com/superprogrammer/user/service/RedisVerificationCodeStore.java` — Redis verification code store.
- `file-keeper/server/src/main/java/com/superprogrammer/user/service/VerificationService.java` — send/check/consume verification logic.
- `file-keeper/server/src/main/java/com/superprogrammer/user/service/UserAuthService.java` — register/login/refresh/logout logic.
- `file-keeper/server/src/main/java/com/superprogrammer/user/controller/VerificationController.java` — client verification endpoints.
- `file-keeper/server/src/main/java/com/superprogrammer/user/controller/ClientAuthController.java` — client auth endpoints.
- `file-keeper/server/src/main/java/com/superprogrammer/audit/service/AdminAuditLogService.java` — admin audit insert logic.
- `file-keeper/server/src/main/java/com/superprogrammer/admin/dto/UserReviewRequest.java` — approve/disable/enable request.
- `file-keeper/server/src/main/java/com/superprogrammer/admin/controller/AdminAuthController.java` — admin auth endpoints.
- `file-keeper/server/src/main/java/com/superprogrammer/admin/controller/AdminUserController.java` — admin user management endpoints.
- `file-keeper/server/src/main/java/com/superprogrammer/admin/service/AdminUserService.java` — admin user management logic.

### Create test source

- `file-keeper/server/src/test/java/com/superprogrammer/security/JwtServiceTest.java` — JWT unit/integration test.
- `file-keeper/server/src/test/java/com/superprogrammer/support/InMemoryRefreshTokenStore.java` — test refresh token store.
- `file-keeper/server/src/test/java/com/superprogrammer/support/InMemoryVerificationCodeStore.java` — test verification store.
- `file-keeper/server/src/test/java/com/superprogrammer/support/TestStoreConfig.java` — test profile store beans; Task 2 starts with verification store only, Task 3 extends it with refresh token store.
- `file-keeper/server/src/test/java/com/superprogrammer/user/UserRegistrationTest.java` — verification/register API test.
- `file-keeper/server/src/test/java/com/superprogrammer/user/UserLoginTest.java` — client login/refresh/logout API test.
- `file-keeper/server/src/test/java/com/superprogrammer/admin/AdminAuthControllerTest.java` — admin login/security API test.
- `file-keeper/server/src/test/java/com/superprogrammer/admin/AdminUserReviewTest.java` — approve/list/detail audit API test.
- `file-keeper/server/src/test/java/com/superprogrammer/admin/AdminUserDisableEnableTest.java` — disable/enable/revoke API test.

---

## Shared Constants

Use these exact string values throughout phase 2:

```java
public final class AuthConstants {
    public static final String ROLE_SUPER_ADMIN = "super_admin";
    public static final String ROLE_USER = "user";
    public static final String STATUS_PENDING_VERIFICATION = "pending_verification";
    public static final String STATUS_PENDING_REVIEW = "pending_review";
    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_DISABLED = "disabled";

    private AuthConstants() {
    }
}
```

Create this as:

- `file-keeper/server/src/main/java/com/superprogrammer/security/AuthConstants.java`

---

### Task 1: JWT, security filter, auth configuration, and exception responses

**Files:**
- Modify: `file-keeper/server/pom.xml`
- Modify: `file-keeper/server/src/main/resources/application.yml`
- Modify: `file-keeper/server/src/test/resources/application-test.yml`
- Create: `file-keeper/server/src/main/java/com/superprogrammer/common/GlobalExceptionHandler.java`
- Create: `file-keeper/server/src/main/java/com/superprogrammer/config/AuthProperties.java`
- Create: `file-keeper/server/src/main/java/com/superprogrammer/security/AuthConstants.java`
- Create: `file-keeper/server/src/main/java/com/superprogrammer/security/AuthPrincipal.java`
- Create: `file-keeper/server/src/main/java/com/superprogrammer/security/JwtService.java`
- Create: `file-keeper/server/src/main/java/com/superprogrammer/security/JwtAuthenticationFilter.java`
- Create: `file-keeper/server/src/main/java/com/superprogrammer/security/SecurityConfig.java`
- Test: `file-keeper/server/src/test/java/com/superprogrammer/security/JwtServiceTest.java`

- [ ] **Step 1: Write the failing JWT test**

Create `file-keeper/server/src/test/java/com/superprogrammer/security/JwtServiceTest.java`:

```java
package com.superprogrammer.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@ActiveProfiles("test")
class JwtServiceTest {

    @Autowired
    private JwtService jwtService;

    @Test
    void createsAndParsesAccessToken() {
        String token = jwtService.createAccessToken(42L, "super_admin", "active");

        AuthPrincipal principal = jwtService.parseAccessToken(token);

        assertEquals(42L, principal.userId());
        assertEquals("super_admin", principal.role());
        assertEquals("active", principal.status());
    }

    @Test
    void rejectsMalformedToken() {
        assertThrows(RuntimeException.class, () -> jwtService.parseAccessToken("not-a-jwt"));
    }
}
```

- [ ] **Step 2: Run the JWT test and verify it fails**

Run:

```bash
JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot" MAVEN_HOME="/c/Users/19536/.local/tools/apache-maven-3.9.11" PATH="/c/Users/19536/.local/tools/apache-maven-3.9.11/bin:/c/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot/bin:$PATH" mvn -f "file-keeper/server/pom.xml" test -Dtest=JwtServiceTest
```

Expected: compile failure because `JwtService` and `AuthPrincipal` do not exist.

- [ ] **Step 3: Add JJWT dependencies**

Modify `file-keeper/server/pom.xml` inside `<dependencies>` after the Redis dependency:

```xml
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-api</artifactId>
            <version>0.12.5</version>
        </dependency>
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-impl</artifactId>
            <version>0.12.5</version>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-jackson</artifactId>
            <version>0.12.5</version>
            <scope>runtime</scope>
        </dependency>
```

- [ ] **Step 4: Add auth settings**

Append to `file-keeper/server/src/main/resources/application.yml` under existing `file-keeper:`:

```yaml
  auth:
    jwt:
      secret: ${FILE_KEEPER_JWT_SECRET:change-this-file-keeper-jwt-secret-at-least-32-bytes}
      access-token-minutes: 15
    refresh-token:
      days: 7
    verification:
      code-minutes: 10
      verified-minutes: 30
      dev-fixed-code: ${FILE_KEEPER_VERIFICATION_DEV_FIXED_CODE:}
```

Append to `file-keeper/server/src/test/resources/application-test.yml` under existing `file-keeper:`:

```yaml
  auth:
    jwt:
      secret: test-file-keeper-jwt-secret-at-least-32-bytes
      access-token-minutes: 15
    refresh-token:
      days: 7
    verification:
      code-minutes: 10
      verified-minutes: 30
      dev-fixed-code: "123456"
```

- [ ] **Step 5: Create auth constants and principal**

Create `file-keeper/server/src/main/java/com/superprogrammer/security/AuthConstants.java`:

```java
package com.superprogrammer.security;

public final class AuthConstants {

    public static final String ROLE_SUPER_ADMIN = "super_admin";
    public static final String ROLE_USER = "user";
    public static final String STATUS_PENDING_VERIFICATION = "pending_verification";
    public static final String STATUS_PENDING_REVIEW = "pending_review";
    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_DISABLED = "disabled";

    private AuthConstants() {
    }
}
```

Create `file-keeper/server/src/main/java/com/superprogrammer/security/AuthPrincipal.java`:

```java
package com.superprogrammer.security;

public record AuthPrincipal(Long userId, String role, String status) {
}
```

- [ ] **Step 6: Create auth properties**

Create `file-keeper/server/src/main/java/com/superprogrammer/config/AuthProperties.java`:

```java
package com.superprogrammer.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "file-keeper.auth")
public class AuthProperties {

    private Jwt jwt = new Jwt();
    private RefreshToken refreshToken = new RefreshToken();
    private Verification verification = new Verification();

    @Data
    public static class Jwt {
        private String secret;
        private long accessTokenMinutes = 15;
    }

    @Data
    public static class RefreshToken {
        private long days = 7;
    }

    @Data
    public static class Verification {
        private long codeMinutes = 10;
        private long verifiedMinutes = 30;
        private String devFixedCode;
    }
}
```

- [ ] **Step 7: Create JWT service**

Create `file-keeper/server/src/main/java/com/superprogrammer/security/JwtService.java`:

```java
package com.superprogrammer.security;

import com.superprogrammer.config.AuthProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

@Service
@RequiredArgsConstructor
public class JwtService {

    private final AuthProperties authProperties;

    public String createAccessToken(Long userId, String role, String status) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(authProperties.getJwt().getAccessTokenMinutes(), ChronoUnit.MINUTES);
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("role", role)
                .claim("status", status)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(signingKey())
                .compact();
    }

    public AuthPrincipal parseAccessToken(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(signingKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return new AuthPrincipal(
                Long.valueOf(claims.getSubject()),
                claims.get("role", String.class),
                claims.get("status", String.class)
        );
    }

    private SecretKey signingKey() {
        byte[] secret = authProperties.getJwt().getSecret().getBytes(StandardCharsets.UTF_8);
        return Keys.hmacShaKeyFor(secret);
    }
}
```

- [ ] **Step 8: Create JWT filter and security config**

Create `file-keeper/server/src/main/java/com/superprogrammer/security/JwtAuthenticationFilter.java`:

```java
package com.superprogrammer.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        if (StringUtils.hasText(authorization) && authorization.startsWith("Bearer ")) {
            String token = authorization.substring(7);
            try {
                AuthPrincipal principal = jwtService.parseAccessToken(token);
                UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                        principal,
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + principal.role().toUpperCase()))
                );
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (RuntimeException ignored) {
                SecurityContextHolder.clearContext();
            }
        }
        filterChain.doFilter(request, response);
    }
}
```

Create `file-keeper/server/src/main/java/com/superprogrammer/security/SecurityConfig.java`:

```java
package com.superprogrammer.security;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/client/verification/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/client/auth/register").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/client/auth/login").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/client/auth/refresh").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/client/auth/logout").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/admin/auth/login").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/admin/auth/refresh").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/admin/auth/logout").permitAll()
                        .requestMatchers("/api/admin/**").hasRole("SUPER_ADMIN")
                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
```

- [ ] **Step 9: Create global exception handler**

Create `file-keeper/server/src/main/java/com/superprogrammer/common/GlobalExceptionHandler.java`:

```java
package com.superprogrammer.common;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<R<Void>> handleBusinessException(BusinessException exception) {
        HttpStatus status = HttpStatus.valueOf(exception.getCode() >= 100 && exception.getCode() <= 599 ? exception.getCode() : 500);
        return ResponseEntity.status(status).body(R.fail(exception.getCode(), exception.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<R<Void>> handleValidationException(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .orElse(ErrorCode.BAD_REQUEST.getMsg());
        return ResponseEntity.badRequest().body(R.fail(ErrorCode.BAD_REQUEST.getCode(), message));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<R<Void>> handleAuthenticationException(AuthenticationException exception) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(R.fail(ErrorCode.UNAUTHORIZED));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<R<Void>> handleAccessDeniedException(AccessDeniedException exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(R.fail(ErrorCode.FORBIDDEN));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<R<Void>> handleException(Exception exception) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(R.fail(ErrorCode.INTERNAL_ERROR));
    }
}
```

- [ ] **Step 10: Run the JWT test and all existing tests**

Run:

```bash
JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot" MAVEN_HOME="/c/Users/19536/.local/tools/apache-maven-3.9.11" PATH="/c/Users/19536/.local/tools/apache-maven-3.9.11/bin:/c/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot/bin:$PATH" mvn -f "file-keeper/server/pom.xml" test -Dtest=JwtServiceTest
```

Expected: `Tests run: 2, Failures: 0, Errors: 0`.

Run:

```bash
JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot" MAVEN_HOME="/c/Users/19536/.local/tools/apache-maven-3.9.11" PATH="/c/Users/19536/.local/tools/apache-maven-3.9.11/bin:/c/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot/bin:$PATH" mvn -f "file-keeper/server/pom.xml" test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 11: Commit Task 1**

```bash
git add "file-keeper/server/pom.xml" "file-keeper/server/src/main/resources/application.yml" "file-keeper/server/src/test/resources/application-test.yml" "file-keeper/server/src/main/java/com/superprogrammer/common/GlobalExceptionHandler.java" "file-keeper/server/src/main/java/com/superprogrammer/config/AuthProperties.java" "file-keeper/server/src/main/java/com/superprogrammer/security/AuthConstants.java" "file-keeper/server/src/main/java/com/superprogrammer/security/AuthPrincipal.java" "file-keeper/server/src/main/java/com/superprogrammer/security/JwtService.java" "file-keeper/server/src/main/java/com/superprogrammer/security/JwtAuthenticationFilter.java" "file-keeper/server/src/main/java/com/superprogrammer/security/SecurityConfig.java" "file-keeper/server/src/test/java/com/superprogrammer/security/JwtServiceTest.java"
git commit -m "feat: 添加商业授权服务端JWT认证基础"
```

---

### Task 2: Verification send/check and client registration

**Files:**
- Create: `file-keeper/server/src/main/java/com/superprogrammer/user/dto/SendVerificationRequest.java`
- Create: `file-keeper/server/src/main/java/com/superprogrammer/user/dto/CheckVerificationRequest.java`
- Create: `file-keeper/server/src/main/java/com/superprogrammer/user/dto/VerificationCheckResponse.java`
- Create: `file-keeper/server/src/main/java/com/superprogrammer/user/dto/RegisterRequest.java`
- Create: `file-keeper/server/src/main/java/com/superprogrammer/user/dto/UserSummary.java`
- Create: `file-keeper/server/src/main/java/com/superprogrammer/user/repository/UserRepository.java`
- Create: `file-keeper/server/src/main/java/com/superprogrammer/user/service/VerificationCodeStore.java`
- Create: `file-keeper/server/src/main/java/com/superprogrammer/user/service/RedisVerificationCodeStore.java`
- Create: `file-keeper/server/src/main/java/com/superprogrammer/user/service/VerificationService.java`
- Create: `file-keeper/server/src/main/java/com/superprogrammer/user/service/UserAuthService.java`
- Create: `file-keeper/server/src/main/java/com/superprogrammer/user/controller/VerificationController.java`
- Create: `file-keeper/server/src/main/java/com/superprogrammer/user/controller/ClientAuthController.java`
- Create: `file-keeper/server/src/test/java/com/superprogrammer/support/InMemoryVerificationCodeStore.java`
- Create: `file-keeper/server/src/test/java/com/superprogrammer/support/TestStoreConfig.java`
- Test: `file-keeper/server/src/test/java/com/superprogrammer/user/UserRegistrationTest.java`

- [ ] **Step 1: Write failing registration API tests**

Create `file-keeper/server/src/test/java/com/superprogrammer/user/UserRegistrationTest.java`:

```java
package com.superprogrammer.user;

import com.superprogrammer.support.TestStoreConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestStoreConfig.class)
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:user_registration;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class UserRegistrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void registersEmailUserAfterVerification() throws Exception {
        mockMvc.perform(post("/api/client/verification/send")
                        .contentType("application/json")
                        .content("{\"contactType\":\"email\",\"contact\":\"new-user@example.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(post("/api/client/verification/check")
                        .contentType("application/json")
                        .content("{\"contactType\":\"email\",\"contact\":\"new-user@example.com\",\"code\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.verified").value(true));

        mockMvc.perform(post("/api/client/auth/register")
                        .contentType("application/json")
                        .content("{\"email\":\"new-user@example.com\",\"password\":\"Password123!\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value("new-user@example.com"))
                .andExpect(jsonPath("$.data.role").value("user"))
                .andExpect(jsonPath("$.data.status").value("pending_review"))
                .andExpect(jsonPath("$.data.emailVerified").value(true));

        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from users where email = 'new-user@example.com' and status = 'pending_review' and email_verified = true",
                Integer.class
        );
        assertEquals(1, count);
    }

    @Test
    void rejectsRegistrationWithoutVerifiedContact() throws Exception {
        mockMvc.perform(post("/api/client/auth/register")
                        .contentType("application/json")
                        .content("{\"email\":\"unverified@example.com\",\"password\":\"Password123!\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(422));
    }

    @Test
    void rejectsDuplicateEmailRegistration() throws Exception {
        jdbcTemplate.update(
                "insert into users (email, password_hash, role, status, email_verified, phone_verified, device_limit, offline_cache_minutes, created_by, created_at, updated_by, updated_at, deleted) " +
                        "values ('taken@example.com', 'hash', 'user', 'pending_review', true, false, 1, 0, 0, CURRENT_TIMESTAMP, 0, CURRENT_TIMESTAMP, 0)"
        );

        mockMvc.perform(post("/api/client/verification/send")
                        .contentType("application/json")
                        .content("{\"contactType\":\"email\",\"contact\":\"taken@example.com\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409));
    }
}
```

- [ ] **Step 2: Run registration tests and verify they fail**

Run:

```bash
JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot" MAVEN_HOME="/c/Users/19536/.local/tools/apache-maven-3.9.11" PATH="/c/Users/19536/.local/tools/apache-maven-3.9.11/bin:/c/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot/bin:$PATH" mvn -f "file-keeper/server/pom.xml" test -Dtest=UserRegistrationTest
```

Expected: compile failure because DTOs, controllers, services, repository, and test store config do not exist.

- [ ] **Step 3: Create request/response DTOs**

Create `file-keeper/server/src/main/java/com/superprogrammer/user/dto/SendVerificationRequest.java`:

```java
package com.superprogrammer.user.dto;

import jakarta.validation.constraints.NotBlank;

public record SendVerificationRequest(
        @NotBlank String contactType,
        @NotBlank String contact
) {
}
```

Create `file-keeper/server/src/main/java/com/superprogrammer/user/dto/CheckVerificationRequest.java`:

```java
package com.superprogrammer.user.dto;

import jakarta.validation.constraints.NotBlank;

public record CheckVerificationRequest(
        @NotBlank String contactType,
        @NotBlank String contact,
        @NotBlank String code
) {
}
```

Create `file-keeper/server/src/main/java/com/superprogrammer/user/dto/VerificationCheckResponse.java`:

```java
package com.superprogrammer.user.dto;

public record VerificationCheckResponse(boolean verified) {
}
```

Create `file-keeper/server/src/main/java/com/superprogrammer/user/dto/RegisterRequest.java`:

```java
package com.superprogrammer.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        String email,
        String phone,
        @NotBlank @Size(min = 8, max = 72) String password
) {
}
```

Create `file-keeper/server/src/main/java/com/superprogrammer/user/dto/UserSummary.java`:

```java
package com.superprogrammer.user.dto;

public record UserSummary(
        Long id,
        String email,
        String phone,
        String role,
        String status,
        Boolean emailVerified,
        Boolean phoneVerified,
        Integer deviceLimit,
        Integer offlineCacheMinutes
) {
}
```

- [ ] **Step 4: Create user repository**

Create `file-keeper/server/src/main/java/com/superprogrammer/user/repository/UserRepository.java`:

```java
package com.superprogrammer.user.repository;

import com.superprogrammer.user.dto.UserSummary;
import com.superprogrammer.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class UserRepository {

    private final JdbcTemplate jdbcTemplate;

    public boolean existsByContact(String contactType, String contact) {
        String column = "email".equals(contactType) ? "email" : "phone";
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from users where " + column + " = ? and deleted = 0",
                Integer.class,
                contact
        );
        return count != null && count > 0;
    }

    public UserSummary insertPendingReviewUser(String email, String phone, String passwordHash) {
        boolean emailVerified = email != null;
        boolean phoneVerified = phone != null;
        return jdbcTemplate.queryForObject(
                "insert into users (email, phone, password_hash, role, status, email_verified, phone_verified, device_limit, offline_cache_minutes, created_by, created_at, updated_by, updated_at, deleted) " +
                        "values (?, ?, ?, 'user', 'pending_review', ?, ?, 1, 0, 0, CURRENT_TIMESTAMP, 0, CURRENT_TIMESTAMP, 0) " +
                        "returning id, email, phone, role, status, email_verified, phone_verified, device_limit, offline_cache_minutes",
                userSummaryMapper(),
                email,
                phone,
                passwordHash,
                emailVerified,
                phoneVerified
        );
    }

    public Optional<User> findByIdentifier(String identifier) {
        List<User> users = jdbcTemplate.query(
                "select id, email, phone, password_hash, role, status, email_verified, phone_verified, device_limit, offline_cache_minutes, created_by, created_at, updated_by, updated_at, deleted " +
                        "from users where deleted = 0 and (email = ? or phone = ?) limit 1",
                userMapper(),
                identifier,
                identifier
        );
        return users.stream().findFirst();
    }

    public Optional<User> findById(Long id) {
        List<User> users = jdbcTemplate.query(
                "select id, email, phone, password_hash, role, status, email_verified, phone_verified, device_limit, offline_cache_minutes, created_by, created_at, updated_by, updated_at, deleted " +
                        "from users where id = ? and deleted = 0",
                userMapper(),
                id
        );
        return users.stream().findFirst();
    }

    public UserSummary toSummary(User user) {
        return new UserSummary(
                user.getId(),
                user.getEmail(),
                user.getPhone(),
                user.getRole(),
                user.getStatus(),
                user.getEmailVerified(),
                user.getPhoneVerified(),
                user.getDeviceLimit(),
                user.getOfflineCacheMinutes()
        );
    }

    private RowMapper<UserSummary> userSummaryMapper() {
        return (rs, rowNum) -> new UserSummary(
                rs.getLong("id"),
                rs.getString("email"),
                rs.getString("phone"),
                rs.getString("role"),
                rs.getString("status"),
                rs.getBoolean("email_verified"),
                rs.getBoolean("phone_verified"),
                rs.getInt("device_limit"),
                rs.getInt("offline_cache_minutes")
        );
    }

    private RowMapper<User> userMapper() {
        return new RowMapper<>() {
            @Override
            public User mapRow(ResultSet rs, int rowNum) throws SQLException {
                User user = new User();
                user.setId(rs.getLong("id"));
                user.setEmail(rs.getString("email"));
                user.setPhone(rs.getString("phone"));
                user.setPasswordHash(rs.getString("password_hash"));
                user.setRole(rs.getString("role"));
                user.setStatus(rs.getString("status"));
                user.setEmailVerified(rs.getBoolean("email_verified"));
                user.setPhoneVerified(rs.getBoolean("phone_verified"));
                user.setDeviceLimit(rs.getInt("device_limit"));
                user.setOfflineCacheMinutes(rs.getInt("offline_cache_minutes"));
                return user;
            }
        };
    }
}
```

- [ ] **Step 5: Create verification stores**

Create `file-keeper/server/src/main/java/com/superprogrammer/user/service/VerificationCodeStore.java`:

```java
package com.superprogrammer.user.service;

import java.time.Duration;

public interface VerificationCodeStore {

    void saveCode(String contactType, String contact, String code, Duration ttl);

    boolean matchesCode(String contactType, String contact, String code);

    void markVerified(String contactType, String contact, Duration ttl);

    boolean consumeVerified(String contactType, String contact);
}
```

Create `file-keeper/server/src/main/java/com/superprogrammer/user/service/RedisVerificationCodeStore.java`:

```java
package com.superprogrammer.user.service;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@Profile("!test")
@RequiredArgsConstructor
public class RedisVerificationCodeStore implements VerificationCodeStore {

    private final StringRedisTemplate redisTemplate;

    @Override
    public void saveCode(String contactType, String contact, String code, Duration ttl) {
        redisTemplate.opsForValue().set(codeKey(contactType, contact), code, ttl);
    }

    @Override
    public boolean matchesCode(String contactType, String contact, String code) {
        return code.equals(redisTemplate.opsForValue().get(codeKey(contactType, contact)));
    }

    @Override
    public void markVerified(String contactType, String contact, Duration ttl) {
        redisTemplate.opsForValue().set(verifiedKey(contactType, contact), "1", ttl);
    }

    @Override
    public boolean consumeVerified(String contactType, String contact) {
        String key = verifiedKey(contactType, contact);
        Boolean hasKey = redisTemplate.hasKey(key);
        if (Boolean.TRUE.equals(hasKey)) {
            redisTemplate.delete(key);
            redisTemplate.delete(codeKey(contactType, contact));
            return true;
        }
        return false;
    }

    private String codeKey(String contactType, String contact) {
        return "fk:verification:code:" + contactType + ":" + contact;
    }

    private String verifiedKey(String contactType, String contact) {
        return "fk:verification:verified:" + contactType + ":" + contact;
    }
}
```

Create `file-keeper/server/src/test/java/com/superprogrammer/support/InMemoryVerificationCodeStore.java`:

```java
package com.superprogrammer.support;

import com.superprogrammer.user.service.VerificationCodeStore;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryVerificationCodeStore implements VerificationCodeStore {

    private final Map<String, String> codes = new ConcurrentHashMap<>();
    private final Map<String, Boolean> verified = new ConcurrentHashMap<>();

    @Override
    public void saveCode(String contactType, String contact, String code, Duration ttl) {
        codes.put(codeKey(contactType, contact), code);
    }

    @Override
    public boolean matchesCode(String contactType, String contact, String code) {
        return code.equals(codes.get(codeKey(contactType, contact)));
    }

    @Override
    public void markVerified(String contactType, String contact, Duration ttl) {
        verified.put(verifiedKey(contactType, contact), true);
    }

    @Override
    public boolean consumeVerified(String contactType, String contact) {
        String verifiedKey = verifiedKey(contactType, contact);
        boolean exists = Boolean.TRUE.equals(verified.remove(verifiedKey));
        if (exists) {
            codes.remove(codeKey(contactType, contact));
        }
        return exists;
    }

    private String codeKey(String contactType, String contact) {
        return contactType + ":" + contact;
    }

    private String verifiedKey(String contactType, String contact) {
        return contactType + ":" + contact;
    }
}
```

Create `file-keeper/server/src/test/java/com/superprogrammer/support/TestStoreConfig.java`:

```java
package com.superprogrammer.support;

import com.superprogrammer.user.service.VerificationCodeStore;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration
public class TestStoreConfig {

    @Bean
    @Primary
    public VerificationCodeStore verificationCodeStore() {
        return new InMemoryVerificationCodeStore();
    }
}
```

- [ ] **Step 6: Create verification service**

Create `file-keeper/server/src/main/java/com/superprogrammer/user/service/VerificationService.java`:

```java
package com.superprogrammer.user.service;

import com.superprogrammer.common.BusinessException;
import com.superprogrammer.common.ErrorCode;
import com.superprogrammer.config.AuthProperties;
import com.superprogrammer.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.time.Duration;

@Service
@RequiredArgsConstructor
public class VerificationService {

    private final AuthProperties authProperties;
    private final VerificationCodeStore verificationCodeStore;
    private final UserRepository userRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    public void send(String contactType, String contact) {
        String normalizedType = normalizeContactType(contactType);
        String normalizedContact = normalizeContact(normalizedType, contact);
        if (userRepository.existsByContact(normalizedType, normalizedContact)) {
            throw new BusinessException(ErrorCode.CONFLICT, "联系方式已注册");
        }
        String code = generateCode();
        verificationCodeStore.saveCode(
                normalizedType,
                normalizedContact,
                code,
                Duration.ofMinutes(authProperties.getVerification().getCodeMinutes())
        );
    }

    public boolean check(String contactType, String contact, String code) {
        String normalizedType = normalizeContactType(contactType);
        String normalizedContact = normalizeContact(normalizedType, contact);
        if (!verificationCodeStore.matchesCode(normalizedType, normalizedContact, code)) {
            throw new BusinessException(ErrorCode.UNPROCESSABLE, "验证码错误或已过期");
        }
        verificationCodeStore.markVerified(
                normalizedType,
                normalizedContact,
                Duration.ofMinutes(authProperties.getVerification().getVerifiedMinutes())
        );
        return true;
    }

    public void consumeVerified(String contactType, String contact) {
        String normalizedType = normalizeContactType(contactType);
        String normalizedContact = normalizeContact(normalizedType, contact);
        if (!verificationCodeStore.consumeVerified(normalizedType, normalizedContact)) {
            throw new BusinessException(ErrorCode.UNPROCESSABLE, "请先完成联系方式验证");
        }
    }

    public String normalizeContactType(String contactType) {
        if (!"email".equals(contactType) && !"phone".equals(contactType)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "联系方式类型必须是 email 或 phone");
        }
        return contactType;
    }

    public String normalizeContact(String contactType, String contact) {
        if (!StringUtils.hasText(contact)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "联系方式不能为空");
        }
        String normalized = contact.trim();
        return "email".equals(contactType) ? normalized.toLowerCase() : normalized;
    }

    private String generateCode() {
        String fixedCode = authProperties.getVerification().getDevFixedCode();
        if (StringUtils.hasText(fixedCode)) {
            return fixedCode;
        }
        return String.format("%06d", secureRandom.nextInt(1_000_000));
    }
}
```

- [ ] **Step 7: Create registration service method**

Create `file-keeper/server/src/main/java/com/superprogrammer/user/service/UserAuthService.java` with registration only in this task:

```java
package com.superprogrammer.user.service;

import com.superprogrammer.common.BusinessException;
import com.superprogrammer.common.ErrorCode;
import com.superprogrammer.security.AuthConstants;
import com.superprogrammer.user.dto.RegisterRequest;
import com.superprogrammer.user.dto.UserSummary;
import com.superprogrammer.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class UserAuthService {

    private final UserRepository userRepository;
    private final VerificationService verificationService;
    private final PasswordEncoder passwordEncoder;

    public UserSummary register(RegisterRequest request) {
        String email = StringUtils.hasText(request.email())
                ? verificationService.normalizeContact("email", request.email())
                : null;
        String phone = StringUtils.hasText(request.phone())
                ? verificationService.normalizeContact("phone", request.phone())
                : null;
        if (email == null && phone == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "邮箱或手机号至少填写一个");
        }
        if (email != null && phone != null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "邮箱和手机号只能选择一个注册");
        }
        String contactType = email != null ? "email" : "phone";
        String contact = email != null ? email : phone;
        if (userRepository.existsByContact(contactType, contact)) {
            throw new BusinessException(ErrorCode.CONFLICT, "联系方式已注册");
        }
        verificationService.consumeVerified(contactType, contact);
        return userRepository.insertPendingReviewUser(email, phone, passwordEncoder.encode(request.password()));
    }
}
```

Task 3 will extend this same class with login/refresh/logout methods. Keep this file focused; do not create a second auth service.

- [ ] **Step 8: Create verification and client auth controllers**

Create `file-keeper/server/src/main/java/com/superprogrammer/user/controller/VerificationController.java`:

```java
package com.superprogrammer.user.controller;

import com.superprogrammer.common.R;
import com.superprogrammer.user.dto.CheckVerificationRequest;
import com.superprogrammer.user.dto.SendVerificationRequest;
import com.superprogrammer.user.dto.VerificationCheckResponse;
import com.superprogrammer.user.service.VerificationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/client/verification")
@RequiredArgsConstructor
public class VerificationController {

    private final VerificationService verificationService;

    @PostMapping("/send")
    public R<Void> send(@Valid @RequestBody SendVerificationRequest request) {
        verificationService.send(request.contactType(), request.contact());
        return R.ok();
    }

    @PostMapping("/check")
    public R<VerificationCheckResponse> check(@Valid @RequestBody CheckVerificationRequest request) {
        boolean verified = verificationService.check(request.contactType(), request.contact(), request.code());
        return R.ok(new VerificationCheckResponse(verified));
    }
}
```

Create `file-keeper/server/src/main/java/com/superprogrammer/user/controller/ClientAuthController.java` with registration only in this task:

```java
package com.superprogrammer.user.controller;

import com.superprogrammer.common.R;
import com.superprogrammer.user.dto.RegisterRequest;
import com.superprogrammer.user.dto.UserSummary;
import com.superprogrammer.user.service.UserAuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/client/auth")
@RequiredArgsConstructor
public class ClientAuthController {

    private final UserAuthService userAuthService;

    @PostMapping("/register")
    public R<UserSummary> register(@Valid @RequestBody RegisterRequest request) {
        return R.ok(userAuthService.register(request));
    }
}
```

- [ ] **Step 9: Run registration tests and all tests**

Run:

```bash
JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot" MAVEN_HOME="/c/Users/19536/.local/tools/apache-maven-3.9.11" PATH="/c/Users/19536/.local/tools/apache-maven-3.9.11/bin:/c/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot/bin:$PATH" mvn -f "file-keeper/server/pom.xml" test -Dtest=UserRegistrationTest
```

Expected: `Tests run: 3, Failures: 0, Errors: 0`.

Run:

```bash
JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot" MAVEN_HOME="/c/Users/19536/.local/tools/apache-maven-3.9.11" PATH="/c/Users/19536/.local/tools/apache-maven-3.9.11/bin:/c/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot/bin:$PATH" mvn -f "file-keeper/server/pom.xml" test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 10: Commit Task 2**

```bash
git add "file-keeper/server/src/main/java/com/superprogrammer/user" "file-keeper/server/src/test/java/com/superprogrammer/support" "file-keeper/server/src/test/java/com/superprogrammer/user/UserRegistrationTest.java"
git commit -m "feat: 实现客户端验证码注册"
```

---

### Task 3: Client login, refresh token, and logout

**Files:**
- Modify: `file-keeper/server/src/main/java/com/superprogrammer/user/service/UserAuthService.java`
- Modify: `file-keeper/server/src/main/java/com/superprogrammer/user/controller/ClientAuthController.java`
- Create: `file-keeper/server/src/main/java/com/superprogrammer/user/dto/LoginRequest.java`
- Create: `file-keeper/server/src/main/java/com/superprogrammer/user/dto/RefreshTokenRequest.java`
- Create: `file-keeper/server/src/main/java/com/superprogrammer/user/dto/AuthResponse.java`
- Create: `file-keeper/server/src/main/java/com/superprogrammer/security/RefreshTokenStore.java`
- Create: `file-keeper/server/src/main/java/com/superprogrammer/security/RedisRefreshTokenStore.java`
- Create: `file-keeper/server/src/main/java/com/superprogrammer/security/RefreshTokenService.java`
- Create: `file-keeper/server/src/test/java/com/superprogrammer/support/InMemoryRefreshTokenStore.java`
- Test: `file-keeper/server/src/test/java/com/superprogrammer/user/UserLoginTest.java`

- [ ] **Step 1: Write failing client login tests**

Create `file-keeper/server/src/test/java/com/superprogrammer/user/UserLoginTest.java`:

```java
package com.superprogrammer.user;

import com.superprogrammer.support.TestStoreConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestStoreConfig.class)
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:user_login;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class UserLoginTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void pendingReviewUserCanLoginAndRefreshAndLogout() throws Exception {
        insertUser("login@example.com", "pending_review");

        MvcResult loginResult = mockMvc.perform(post("/api/client/auth/login")
                        .contentType("application/json")
                        .content("{\"identifier\":\"login@example.com\",\"password\":\"Password123!\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isString())
                .andExpect(jsonPath("$.data.refreshToken").isString())
                .andExpect(jsonPath("$.data.expiresInSeconds").value(900))
                .andExpect(jsonPath("$.data.user.status").value("pending_review"))
                .andReturn();

        String body = loginResult.getResponse().getContentAsString();
        String refreshToken = body.replaceAll(".*\\\"refreshToken\\\":\\\"([^\\\"]+)\\\".*", "$1");

        mockMvc.perform(post("/api/client/auth/refresh")
                        .contentType("application/json")
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isString())
                .andExpect(jsonPath("$.data.refreshToken").value(refreshToken));

        mockMvc.perform(post("/api/client/auth/logout")
                        .contentType("application/json")
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(post("/api/client/auth/refresh")
                        .contentType("application/json")
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void disabledUserCannotLogin() throws Exception {
        insertUser("disabled@example.com", "disabled");

        mockMvc.perform(post("/api/client/auth/login")
                        .contentType("application/json")
                        .content("{\"identifier\":\"disabled@example.com\",\"password\":\"Password123!\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void rejectsWrongPassword() throws Exception {
        insertUser("wrong-password@example.com", "active");

        mockMvc.perform(post("/api/client/auth/login")
                        .contentType("application/json")
                        .content("{\"identifier\":\"wrong-password@example.com\",\"password\":\"bad-password\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    private void insertUser(String email, String status) {
        jdbcTemplate.update(
                "insert into users (email, password_hash, role, status, email_verified, phone_verified, device_limit, offline_cache_minutes, created_by, created_at, updated_by, updated_at, deleted) " +
                        "values (?, ?, 'user', ?, true, false, 1, 0, 0, CURRENT_TIMESTAMP, 0, CURRENT_TIMESTAMP, 0)",
                email,
                passwordEncoder.encode("Password123!"),
                status
        );
    }
}
```

- [ ] **Step 2: Run client login tests and verify they fail**

Run:

```bash
JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot" MAVEN_HOME="/c/Users/19536/.local/tools/apache-maven-3.9.11" PATH="/c/Users/19536/.local/tools/apache-maven-3.9.11/bin:/c/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot/bin:$PATH" mvn -f "file-keeper/server/pom.xml" test -Dtest=UserLoginTest
```

Expected: compile failure because login DTOs, refresh token service, and login controller methods do not exist.

- [ ] **Step 3: Create login/refresh DTOs**

Create `file-keeper/server/src/main/java/com/superprogrammer/user/dto/LoginRequest.java`:

```java
package com.superprogrammer.user.dto;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @NotBlank String identifier,
        @NotBlank String password
) {
}
```

Create `file-keeper/server/src/main/java/com/superprogrammer/user/dto/RefreshTokenRequest.java`:

```java
package com.superprogrammer.user.dto;

import jakarta.validation.constraints.NotBlank;

public record RefreshTokenRequest(@NotBlank String refreshToken) {
}
```

Create `file-keeper/server/src/main/java/com/superprogrammer/user/dto/AuthResponse.java`:

```java
package com.superprogrammer.user.dto;

public record AuthResponse(
        String accessToken,
        String refreshToken,
        long expiresInSeconds,
        UserSummary user
) {
}
```

- [ ] **Step 4: Create refresh token store and service**

Create `file-keeper/server/src/main/java/com/superprogrammer/security/RefreshTokenStore.java`:

```java
package com.superprogrammer.security;

import java.time.Duration;
import java.util.Optional;

public interface RefreshTokenStore {

    void save(String tokenHash, Long userId, Duration ttl);

    Optional<Long> findUserId(String tokenHash);

    void delete(String tokenHash);

    void addTokenToUser(Long userId, String tokenHash, Duration ttl);

    void deleteAllForUser(Long userId);
}
```

Create `file-keeper/server/src/main/java/com/superprogrammer/security/RedisRefreshTokenStore.java`:

```java
package com.superprogrammer.security;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;

@Component
@Profile("!test")
@RequiredArgsConstructor
public class RedisRefreshTokenStore implements RefreshTokenStore {

    private final StringRedisTemplate redisTemplate;

    @Override
    public void save(String tokenHash, Long userId, Duration ttl) {
        redisTemplate.opsForValue().set(tokenKey(tokenHash), String.valueOf(userId), ttl);
    }

    @Override
    public Optional<Long> findUserId(String tokenHash) {
        String value = redisTemplate.opsForValue().get(tokenKey(tokenHash));
        return value == null ? Optional.empty() : Optional.of(Long.valueOf(value));
    }

    @Override
    public void delete(String tokenHash) {
        redisTemplate.delete(tokenKey(tokenHash));
    }

    @Override
    public void addTokenToUser(Long userId, String tokenHash, Duration ttl) {
        String key = userTokensKey(userId);
        redisTemplate.opsForSet().add(key, tokenHash);
        redisTemplate.expire(key, ttl);
    }

    @Override
    public void deleteAllForUser(Long userId) {
        String key = userTokensKey(userId);
        Set<String> tokenHashes = redisTemplate.opsForSet().members(key);
        if (tokenHashes != null) {
            tokenHashes.forEach(this::delete);
        }
        redisTemplate.delete(key);
    }

    private String tokenKey(String tokenHash) {
        return "fk:refresh:" + tokenHash;
    }

    private String userTokensKey(Long userId) {
        return "fk:user:" + userId + ":refresh";
    }
}
```

Create `file-keeper/server/src/test/java/com/superprogrammer/support/InMemoryRefreshTokenStore.java`:

```java
package com.superprogrammer.support;

import com.superprogrammer.security.RefreshTokenStore;

import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryRefreshTokenStore implements RefreshTokenStore {

    private final Map<String, Long> tokens = new ConcurrentHashMap<>();
    private final Map<Long, Set<String>> userTokens = new ConcurrentHashMap<>();

    @Override
    public void save(String tokenHash, Long userId, Duration ttl) {
        tokens.put(tokenHash, userId);
    }

    @Override
    public Optional<Long> findUserId(String tokenHash) {
        return Optional.ofNullable(tokens.get(tokenHash));
    }

    @Override
    public void delete(String tokenHash) {
        Long userId = tokens.remove(tokenHash);
        if (userId != null) {
            userTokens.computeIfAbsent(userId, ignored -> new HashSet<>()).remove(tokenHash);
        }
    }

    @Override
    public void addTokenToUser(Long userId, String tokenHash, Duration ttl) {
        userTokens.computeIfAbsent(userId, ignored -> ConcurrentHashMap.newKeySet()).add(tokenHash);
    }

    @Override
    public void deleteAllForUser(Long userId) {
        Set<String> hashes = userTokens.remove(userId);
        if (hashes != null) {
            hashes.forEach(tokens::remove);
        }
    }
}
```

Modify `file-keeper/server/src/test/java/com/superprogrammer/support/TestStoreConfig.java` to add the refresh token store bean:

```java
package com.superprogrammer.support;

import com.superprogrammer.security.RefreshTokenStore;
import com.superprogrammer.user.service.VerificationCodeStore;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration
public class TestStoreConfig {

    @Bean
    @Primary
    public VerificationCodeStore verificationCodeStore() {
        return new InMemoryVerificationCodeStore();
    }

    @Bean
    @Primary
    public RefreshTokenStore refreshTokenStore() {
        return new InMemoryRefreshTokenStore();
    }
}
```

Create `file-keeper/server/src/main/java/com/superprogrammer/security/RefreshTokenService.java`:

```java
package com.superprogrammer.security;

import com.superprogrammer.common.BusinessException;
import com.superprogrammer.common.ErrorCode;
import com.superprogrammer.config.AuthProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;

@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private final AuthProperties authProperties;
    private final RefreshTokenStore refreshTokenStore;
    private final SecureRandom secureRandom = new SecureRandom();

    public String create(Long userId) {
        byte[] randomBytes = new byte[48];
        secureRandom.nextBytes(randomBytes);
        String token = HexFormat.of().formatHex(randomBytes);
        String hash = hash(token);
        Duration ttl = Duration.ofDays(authProperties.getRefreshToken().getDays());
        refreshTokenStore.save(hash, userId, ttl);
        refreshTokenStore.addTokenToUser(userId, hash, ttl);
        return token;
    }

    public Long requireUserId(String refreshToken) {
        return refreshTokenStore.findUserId(hash(refreshToken))
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED, "refresh token 无效或已过期"));
    }

    public void delete(String refreshToken) {
        refreshTokenStore.delete(hash(refreshToken));
    }

    public void deleteAllForUser(Long userId) {
        refreshTokenStore.deleteAllForUser(userId);
    }

    private String hash(String refreshToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(refreshToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
```

- [ ] **Step 5: Extend auth service with client login/refresh/logout**

Modify `file-keeper/server/src/main/java/com/superprogrammer/user/service/UserAuthService.java` so it contains registration plus these methods:

```java
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;

    public AuthResponse clientLogin(LoginRequest request) {
        User user = userRepository.findByIdentifier(request.identifier().trim())
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED, "账号或密码错误"));
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "账号或密码错误");
        }
        if (AuthConstants.STATUS_DISABLED.equals(user.getStatus())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "账号已禁用");
        }
        return createAuthResponse(user, refreshTokenService.create(user.getId()));
    }

    public AuthResponse refresh(String refreshToken) {
        Long userId = refreshTokenService.requireUserId(refreshToken);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED, "账号不存在"));
        if (AuthConstants.STATUS_DISABLED.equals(user.getStatus())) {
            refreshTokenService.delete(refreshToken);
            throw new BusinessException(ErrorCode.FORBIDDEN, "账号已禁用");
        }
        return createAuthResponse(user, refreshToken);
    }

    public void logout(String refreshToken) {
        refreshTokenService.delete(refreshToken);
    }

    private AuthResponse createAuthResponse(User user, String refreshToken) {
        String accessToken = jwtService.createAccessToken(user.getId(), user.getRole(), user.getStatus());
        long expiresInSeconds = 15 * 60;
        return new AuthResponse(accessToken, refreshToken, expiresInSeconds, userRepository.toSummary(user));
    }
```

Also add imports:

```java
import com.superprogrammer.security.JwtService;
import com.superprogrammer.security.RefreshTokenService;
import com.superprogrammer.user.dto.AuthResponse;
import com.superprogrammer.user.dto.LoginRequest;
import com.superprogrammer.user.entity.User;
```

- [ ] **Step 6: Extend client auth controller with login/refresh/logout**

Modify `file-keeper/server/src/main/java/com/superprogrammer/user/controller/ClientAuthController.java` to add methods:

```java
    @PostMapping("/login")
    public R<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return R.ok(userAuthService.clientLogin(request));
    }

    @PostMapping("/refresh")
    public R<AuthResponse> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return R.ok(userAuthService.refresh(request.refreshToken()));
    }

    @PostMapping("/logout")
    public R<Void> logout(@Valid @RequestBody RefreshTokenRequest request) {
        userAuthService.logout(request.refreshToken());
        return R.ok();
    }
```

Also add imports:

```java
import com.superprogrammer.user.dto.AuthResponse;
import com.superprogrammer.user.dto.LoginRequest;
import com.superprogrammer.user.dto.RefreshTokenRequest;
```

- [ ] **Step 7: Run client login tests and all tests**

Run:

```bash
JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot" MAVEN_HOME="/c/Users/19536/.local/tools/apache-maven-3.9.11" PATH="/c/Users/19536/.local/tools/apache-maven-3.9.11/bin:/c/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot/bin:$PATH" mvn -f "file-keeper/server/pom.xml" test -Dtest=UserLoginTest
```

Expected: `Tests run: 3, Failures: 0, Errors: 0`.

Run:

```bash
JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot" MAVEN_HOME="/c/Users/19536/.local/tools/apache-maven-3.9.11" PATH="/c/Users/19536/.local/tools/apache-maven-3.9.11/bin:/c/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot/bin:$PATH" mvn -f "file-keeper/server/pom.xml" test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 8: Commit Task 3**

```bash
git add "file-keeper/server/src/main/java/com/superprogrammer/security/RefreshTokenStore.java" "file-keeper/server/src/main/java/com/superprogrammer/security/RedisRefreshTokenStore.java" "file-keeper/server/src/main/java/com/superprogrammer/security/RefreshTokenService.java" "file-keeper/server/src/main/java/com/superprogrammer/user/dto/LoginRequest.java" "file-keeper/server/src/main/java/com/superprogrammer/user/dto/RefreshTokenRequest.java" "file-keeper/server/src/main/java/com/superprogrammer/user/dto/AuthResponse.java" "file-keeper/server/src/main/java/com/superprogrammer/user/service/UserAuthService.java" "file-keeper/server/src/main/java/com/superprogrammer/user/controller/ClientAuthController.java" "file-keeper/server/src/test/java/com/superprogrammer/support/InMemoryRefreshTokenStore.java" "file-keeper/server/src/test/java/com/superprogrammer/user/UserLoginTest.java"
git commit -m "feat: 实现客户端登录和刷新令牌"
```

---

### Task 4: Admin login, admin refresh, and admin endpoint protection

**Files:**
- Modify: `file-keeper/server/src/main/java/com/superprogrammer/user/service/UserAuthService.java`
- Create: `file-keeper/server/src/main/java/com/superprogrammer/admin/controller/AdminAuthController.java`
- Test: `file-keeper/server/src/test/java/com/superprogrammer/admin/AdminAuthControllerTest.java`

- [ ] **Step 1: Write failing admin auth tests**

Create `file-keeper/server/src/test/java/com/superprogrammer/admin/AdminAuthControllerTest.java`:

```java
package com.superprogrammer.admin;

import com.superprogrammer.support.TestStoreConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestStoreConfig.class)
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:admin_auth;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AdminAuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void superAdminCanLoginAndRefresh() throws Exception {
        insertUser("admin@example.com", "super_admin", "active");

        MvcResult loginResult = mockMvc.perform(post("/api/admin/auth/login")
                        .contentType("application/json")
                        .content("{\"identifier\":\"admin@example.com\",\"password\":\"AdminPass123!\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isString())
                .andExpect(jsonPath("$.data.refreshToken").isString())
                .andExpect(jsonPath("$.data.user.role").value("super_admin"))
                .andReturn();

        String body = loginResult.getResponse().getContentAsString();
        String refreshToken = body.replaceAll(".*\\\"refreshToken\\\":\\\"([^\\\"]+)\\\".*", "$1");

        mockMvc.perform(post("/api/admin/auth/refresh")
                        .contentType("application/json")
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user.role").value("super_admin"));
    }

    @Test
    void normalUserCannotLoginToAdminApi() throws Exception {
        insertUser("normal@example.com", "user", "active");

        mockMvc.perform(post("/api/admin/auth/login")
                        .contentType("application/json")
                        .content("{\"identifier\":\"normal@example.com\",\"password\":\"AdminPass123!\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void adminUserApisRequireAdminToken() throws Exception {
        mockMvc.perform(get("/api/admin/users"))
                .andExpect(status().isForbidden());
    }

    private void insertUser(String email, String role, String status) {
        jdbcTemplate.update(
                "insert into users (email, password_hash, role, status, email_verified, phone_verified, device_limit, offline_cache_minutes, created_by, created_at, updated_by, updated_at, deleted) " +
                        "values (?, ?, ?, ?, true, false, 1, 0, 0, CURRENT_TIMESTAMP, 0, CURRENT_TIMESTAMP, 0)",
                email,
                passwordEncoder.encode("AdminPass123!"),
                role,
                status
        );
    }
}
```

- [ ] **Step 2: Run admin auth tests and verify they fail**

Run:

```bash
JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot" MAVEN_HOME="/c/Users/19536/.local/tools/apache-maven-3.9.11" PATH="/c/Users/19536/.local/tools/apache-maven-3.9.11/bin:/c/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot/bin:$PATH" mvn -f "file-keeper/server/pom.xml" test -Dtest=AdminAuthControllerTest
```

Expected: compile failure because `AdminAuthController` and admin login service methods do not exist.

- [ ] **Step 3: Extend auth service with admin login and admin refresh**

Modify `file-keeper/server/src/main/java/com/superprogrammer/user/service/UserAuthService.java` to add methods:

```java
    public AuthResponse adminLogin(LoginRequest request) {
        User user = userRepository.findByIdentifier(request.identifier().trim())
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED, "账号或密码错误"));
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "账号或密码错误");
        }
        if (!AuthConstants.ROLE_SUPER_ADMIN.equals(user.getRole())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无管理员权限");
        }
        if (!AuthConstants.STATUS_ACTIVE.equals(user.getStatus())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "管理员账号不可用");
        }
        return createAuthResponse(user, refreshTokenService.create(user.getId()));
    }

    public AuthResponse adminRefresh(String refreshToken) {
        AuthResponse response = refresh(refreshToken);
        if (!AuthConstants.ROLE_SUPER_ADMIN.equals(response.user().role())) {
            refreshTokenService.delete(refreshToken);
            throw new BusinessException(ErrorCode.FORBIDDEN, "无管理员权限");
        }
        if (!AuthConstants.STATUS_ACTIVE.equals(response.user().status())) {
            refreshTokenService.delete(refreshToken);
            throw new BusinessException(ErrorCode.FORBIDDEN, "管理员账号不可用");
        }
        return response;
    }
```

- [ ] **Step 4: Create admin auth controller**

Create `file-keeper/server/src/main/java/com/superprogrammer/admin/controller/AdminAuthController.java`:

```java
package com.superprogrammer.admin.controller;

import com.superprogrammer.common.R;
import com.superprogrammer.user.dto.AuthResponse;
import com.superprogrammer.user.dto.LoginRequest;
import com.superprogrammer.user.dto.RefreshTokenRequest;
import com.superprogrammer.user.service.UserAuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/auth")
@RequiredArgsConstructor
public class AdminAuthController {

    private final UserAuthService userAuthService;

    @PostMapping("/login")
    public R<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return R.ok(userAuthService.adminLogin(request));
    }

    @PostMapping("/refresh")
    public R<AuthResponse> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return R.ok(userAuthService.adminRefresh(request.refreshToken()));
    }

    @PostMapping("/logout")
    public R<Void> logout(@Valid @RequestBody RefreshTokenRequest request) {
        userAuthService.logout(request.refreshToken());
        return R.ok();
    }
}
```

- [ ] **Step 5: Run admin auth tests and all tests**

Run:

```bash
JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot" MAVEN_HOME="/c/Users/19536/.local/tools/apache-maven-3.9.11" PATH="/c/Users/19536/.local/tools/apache-maven-3.9.11/bin:/c/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot/bin:$PATH" mvn -f "file-keeper/server/pom.xml" test -Dtest=AdminAuthControllerTest
```

Expected: `Tests run: 3, Failures: 0, Errors: 0`.

Run:

```bash
JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot" MAVEN_HOME="/c/Users/19536/.local/tools/apache-maven-3.9.11" PATH="/c/Users/19536/.local/tools/apache-maven-3.9.11/bin:/c/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot/bin:$PATH" mvn -f "file-keeper/server/pom.xml" test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 6: Commit Task 4**

```bash
git add "file-keeper/server/src/main/java/com/superprogrammer/admin/controller/AdminAuthController.java" "file-keeper/server/src/main/java/com/superprogrammer/user/service/UserAuthService.java" "file-keeper/server/src/test/java/com/superprogrammer/admin/AdminAuthControllerTest.java"
git commit -m "feat: 实现管理员登录接口"
```

---

### Task 5: Admin user list, detail, approve, and audit log

**Files:**
- Modify: `file-keeper/server/src/main/java/com/superprogrammer/user/repository/UserRepository.java`
- Create: `file-keeper/server/src/main/java/com/superprogrammer/audit/service/AdminAuditLogService.java`
- Create: `file-keeper/server/src/main/java/com/superprogrammer/admin/dto/UserReviewRequest.java`
- Create: `file-keeper/server/src/main/java/com/superprogrammer/admin/service/AdminUserService.java`
- Create: `file-keeper/server/src/main/java/com/superprogrammer/admin/controller/AdminUserController.java`
- Test: `file-keeper/server/src/test/java/com/superprogrammer/admin/AdminUserReviewTest.java`

- [ ] **Step 1: Write failing admin user review tests**

Create `file-keeper/server/src/test/java/com/superprogrammer/admin/AdminUserReviewTest.java`:

```java
package com.superprogrammer.admin;

import com.superprogrammer.support.TestStoreConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestStoreConfig.class)
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:admin_user_review;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AdminUserReviewTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void adminCanListDetailAndApprovePendingUser() throws Exception {
        Long adminId = insertUser("admin@example.com", "super_admin", "active");
        Long userId = insertUser("pending@example.com", "user", "pending_review");
        String accessToken = adminAccessToken();

        mockMvc.perform(get("/api/admin/users?status=pending_review&page=1&size=20")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].email").value("pending@example.com"))
                .andExpect(jsonPath("$.data.total").value(1));

        mockMvc.perform(get("/api/admin/users/" + userId)
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("pending_review"));

        mockMvc.perform(post("/api/admin/users/" + userId + "/approve")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType("application/json")
                        .content("{\"note\":\"资料确认通过\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("active"));

        String status = jdbcTemplate.queryForObject("select status from users where id = ?", String.class, userId);
        assertEquals("active", status);

        Integer auditCount = jdbcTemplate.queryForObject(
                "select count(*) from admin_audit_logs where admin_user_id = ? and action = 'user.approve' and target_type = 'user' and target_id = ?",
                Integer.class,
                adminId,
                String.valueOf(userId)
        );
        assertEquals(1, auditCount);
    }

    private String adminAccessToken() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/admin/auth/login")
                        .contentType("application/json")
                        .content("{\"identifier\":\"admin@example.com\",\"password\":\"AdminPass123!\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return result.getResponse().getContentAsString().replaceAll(".*\\\"accessToken\\\":\\\"([^\\\"]+)\\\".*", "$1");
    }

    private Long insertUser(String email, String role, String status) {
        return jdbcTemplate.queryForObject(
                "insert into users (email, password_hash, role, status, email_verified, phone_verified, device_limit, offline_cache_minutes, created_by, created_at, updated_by, updated_at, deleted) " +
                        "values (?, ?, ?, ?, true, false, 1, 0, 0, CURRENT_TIMESTAMP, 0, CURRENT_TIMESTAMP, 0) returning id",
                Long.class,
                email,
                passwordEncoder.encode("AdminPass123!"),
                role,
                status
        );
    }
}
```

- [ ] **Step 2: Run admin user review tests and verify they fail**

Run:

```bash
JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot" MAVEN_HOME="/c/Users/19536/.local/tools/apache-maven-3.9.11" PATH="/c/Users/19536/.local/tools/apache-maven-3.9.11/bin:/c/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot/bin:$PATH" mvn -f "file-keeper/server/pom.xml" test -Dtest=AdminUserReviewTest
```

Expected: compile failure because admin user controller/service/review DTO/audit service do not exist.

- [ ] **Step 3: Extend user repository for admin list/detail/status updates**

Add these methods to `file-keeper/server/src/main/java/com/superprogrammer/user/repository/UserRepository.java`:

```java
    public PageResult<UserSummary> list(String status, long page, long size) {
        long safePage = Math.max(page, 1);
        long safeSize = Math.min(Math.max(size, 1), 100);
        long offset = (safePage - 1) * safeSize;
        String where = " where deleted = 0";
        Object[] params = new Object[]{};
        if (status != null && !status.isBlank()) {
            where += " and status = ?";
            params = new Object[]{status};
        }
        Long total = jdbcTemplate.queryForObject("select count(*) from users" + where, Long.class, params);
        Object[] queryParams;
        if (params.length == 0) {
            queryParams = new Object[]{safeSize, offset};
        } else {
            queryParams = new Object[]{status, safeSize, offset};
        }
        List<UserSummary> records = jdbcTemplate.query(
                "select id, email, phone, role, status, email_verified, phone_verified, device_limit, offline_cache_minutes from users" + where +
                        " order by id desc limit ? offset ?",
                userSummaryMapper(),
                queryParams
        );
        return new PageResult<>(records, total == null ? 0 : total, safePage, safeSize);
    }

    public UserSummary requireSummaryById(Long id) {
        return findById(id)
                .map(this::toSummary)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "用户不存在"));
    }

    public User requireById(Long id) {
        return findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "用户不存在"));
    }

    public UserSummary updateStatus(Long id, String status, Long adminUserId) {
        int rows = jdbcTemplate.update(
                "update users set status = ?, updated_by = ?, updated_at = CURRENT_TIMESTAMP where id = ? and deleted = 0",
                status,
                adminUserId,
                id
        );
        if (rows == 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        return requireSummaryById(id);
    }
```

Add imports:

```java
import com.superprogrammer.common.BusinessException;
import com.superprogrammer.common.ErrorCode;
import com.superprogrammer.common.PageResult;
```

- [ ] **Step 4: Create audit service and review request DTO**

Create `file-keeper/server/src/main/java/com/superprogrammer/audit/service/AdminAuditLogService.java`:

```java
package com.superprogrammer.audit.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AdminAuditLogService {

    private final JdbcTemplate jdbcTemplate;

    public void record(Long adminUserId, String action, String targetType, String targetId, String detail) {
        jdbcTemplate.update(
                "insert into admin_audit_logs (admin_user_id, action, target_type, target_id, detail, created_by, created_at, updated_by, updated_at, deleted) " +
                        "values (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP, 0)",
                adminUserId,
                action,
                targetType,
                targetId,
                detail,
                adminUserId,
                adminUserId
        );
    }
}
```

Create `file-keeper/server/src/main/java/com/superprogrammer/admin/dto/UserReviewRequest.java`:

```java
package com.superprogrammer.admin.dto;

public record UserReviewRequest(String note) {
}
```

- [ ] **Step 5: Create admin user service**

Create `file-keeper/server/src/main/java/com/superprogrammer/admin/service/AdminUserService.java`:

```java
package com.superprogrammer.admin.service;

import com.superprogrammer.audit.service.AdminAuditLogService;
import com.superprogrammer.common.BusinessException;
import com.superprogrammer.common.ErrorCode;
import com.superprogrammer.common.PageResult;
import com.superprogrammer.security.AuthConstants;
import com.superprogrammer.user.dto.UserSummary;
import com.superprogrammer.user.entity.User;
import com.superprogrammer.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AdminUserService {

    private final UserRepository userRepository;
    private final AdminAuditLogService auditLogService;

    public PageResult<UserSummary> list(String status, long page, long size) {
        return userRepository.list(status, page, size);
    }

    public UserSummary detail(Long userId) {
        return userRepository.requireSummaryById(userId);
    }

    public UserSummary approve(Long adminUserId, Long userId, String note) {
        User user = userRepository.requireById(userId);
        if (!AuthConstants.STATUS_PENDING_REVIEW.equals(user.getStatus())) {
            throw new BusinessException(ErrorCode.UNPROCESSABLE, "只能审核待审核用户");
        }
        UserSummary summary = userRepository.updateStatus(userId, AuthConstants.STATUS_ACTIVE, adminUserId);
        auditLogService.record(adminUserId, "user.approve", "user", String.valueOf(userId), note);
        return summary;
    }
}
```

- [ ] **Step 6: Create admin user controller**

Create `file-keeper/server/src/main/java/com/superprogrammer/admin/controller/AdminUserController.java`:

```java
package com.superprogrammer.admin.controller;

import com.superprogrammer.admin.dto.UserReviewRequest;
import com.superprogrammer.admin.service.AdminUserService;
import com.superprogrammer.common.PageResult;
import com.superprogrammer.common.R;
import com.superprogrammer.security.AuthPrincipal;
import com.superprogrammer.user.dto.UserSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final AdminUserService adminUserService;

    @GetMapping
    public R<PageResult<UserSummary>> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size
    ) {
        return R.ok(adminUserService.list(status, page, size));
    }

    @GetMapping("/{id}")
    public R<UserSummary> detail(@PathVariable Long id) {
        return R.ok(adminUserService.detail(id));
    }

    @PostMapping("/{id}/approve")
    public R<UserSummary> approve(Authentication authentication, @PathVariable Long id, @RequestBody UserReviewRequest request) {
        AuthPrincipal principal = (AuthPrincipal) authentication.getPrincipal();
        return R.ok(adminUserService.approve(principal.userId(), id, request.note()));
    }
}
```

- [ ] **Step 7: Run admin user review tests and all tests**

Run:

```bash
JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot" MAVEN_HOME="/c/Users/19536/.local/tools/apache-maven-3.9.11" PATH="/c/Users/19536/.local/tools/apache-maven-3.9.11/bin:/c/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot/bin:$PATH" mvn -f "file-keeper/server/pom.xml" test -Dtest=AdminUserReviewTest
```

Expected: `Tests run: 1, Failures: 0, Errors: 0`.

Run:

```bash
JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot" MAVEN_HOME="/c/Users/19536/.local/tools/apache-maven-3.9.11" PATH="/c/Users/19536/.local/tools/apache-maven-3.9.11/bin:/c/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot/bin:$PATH" mvn -f "file-keeper/server/pom.xml" test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 8: Commit Task 5**

```bash
git add "file-keeper/server/src/main/java/com/superprogrammer/audit/service/AdminAuditLogService.java" "file-keeper/server/src/main/java/com/superprogrammer/admin/dto/UserReviewRequest.java" "file-keeper/server/src/main/java/com/superprogrammer/admin/service/AdminUserService.java" "file-keeper/server/src/main/java/com/superprogrammer/admin/controller/AdminUserController.java" "file-keeper/server/src/main/java/com/superprogrammer/user/repository/UserRepository.java" "file-keeper/server/src/test/java/com/superprogrammer/admin/AdminUserReviewTest.java"
git commit -m "feat: 实现管理员用户审核接口"
```

---

### Task 6: Admin disable/enable user and revoke refresh tokens

**Files:**
- Modify: `file-keeper/server/src/main/java/com/superprogrammer/admin/service/AdminUserService.java`
- Modify: `file-keeper/server/src/main/java/com/superprogrammer/admin/controller/AdminUserController.java`
- Test: `file-keeper/server/src/test/java/com/superprogrammer/admin/AdminUserDisableEnableTest.java`

- [ ] **Step 1: Write failing disable/enable tests**

Create `file-keeper/server/src/test/java/com/superprogrammer/admin/AdminUserDisableEnableTest.java`:

```java
package com.superprogrammer.admin;

import com.superprogrammer.support.TestStoreConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestStoreConfig.class)
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:admin_user_disable_enable;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AdminUserDisableEnableTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void disablingUserRevokesRefreshTokensAndEnableAllowsLoginAgain() throws Exception {
        Long adminId = insertUser("admin@example.com", "super_admin", "active");
        Long userId = insertUser("active-user@example.com", "user", "active");
        String adminAccessToken = adminAccessToken();
        String userRefreshToken = loginAndReadRefreshToken("active-user@example.com");

        mockMvc.perform(post("/api/admin/users/" + userId + "/disable")
                        .header("Authorization", "Bearer " + adminAccessToken)
                        .contentType("application/json")
                        .content("{\"note\":\"授权违规\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("disabled"));

        mockMvc.perform(post("/api/client/auth/refresh")
                        .contentType("application/json")
                        .content("{\"refreshToken\":\"" + userRefreshToken + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        mockMvc.perform(post("/api/client/auth/login")
                        .contentType("application/json")
                        .content("{\"identifier\":\"active-user@example.com\",\"password\":\"Password123!\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));

        mockMvc.perform(post("/api/admin/users/" + userId + "/enable")
                        .header("Authorization", "Bearer " + adminAccessToken)
                        .contentType("application/json")
                        .content("{\"note\":\"恢复使用\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("active"));

        mockMvc.perform(post("/api/client/auth/login")
                        .contentType("application/json")
                        .content("{\"identifier\":\"active-user@example.com\",\"password\":\"Password123!\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user.status").value("active"));

        Integer disableAuditCount = jdbcTemplate.queryForObject(
                "select count(*) from admin_audit_logs where admin_user_id = ? and action = 'user.disable' and target_id = ?",
                Integer.class,
                adminId,
                String.valueOf(userId)
        );
        Integer enableAuditCount = jdbcTemplate.queryForObject(
                "select count(*) from admin_audit_logs where admin_user_id = ? and action = 'user.enable' and target_id = ?",
                Integer.class,
                adminId,
                String.valueOf(userId)
        );
        assertEquals(1, disableAuditCount);
        assertEquals(1, enableAuditCount);
    }

    private String adminAccessToken() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/admin/auth/login")
                        .contentType("application/json")
                        .content("{\"identifier\":\"admin@example.com\",\"password\":\"Password123!\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return result.getResponse().getContentAsString().replaceAll(".*\\\"accessToken\\\":\\\"([^\\\"]+)\\\".*", "$1");
    }

    private String loginAndReadRefreshToken(String identifier) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/client/auth/login")
                        .contentType("application/json")
                        .content("{\"identifier\":\"" + identifier + "\",\"password\":\"Password123!\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return result.getResponse().getContentAsString().replaceAll(".*\\\"refreshToken\\\":\\\"([^\\\"]+)\\\".*", "$1");
    }

    private Long insertUser(String email, String role, String status) {
        return jdbcTemplate.queryForObject(
                "insert into users (email, password_hash, role, status, email_verified, phone_verified, device_limit, offline_cache_minutes, created_by, created_at, updated_by, updated_at, deleted) " +
                        "values (?, ?, ?, ?, true, false, 1, 0, 0, CURRENT_TIMESTAMP, 0, CURRENT_TIMESTAMP, 0) returning id",
                Long.class,
                email,
                passwordEncoder.encode("Password123!"),
                role,
                status
        );
    }
}
```

- [ ] **Step 2: Run disable/enable tests and verify they fail**

Run:

```bash
JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot" MAVEN_HOME="/c/Users/19536/.local/tools/apache-maven-3.9.11" PATH="/c/Users/19536/.local/tools/apache-maven-3.9.11/bin:/c/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot/bin:$PATH" mvn -f "file-keeper/server/pom.xml" test -Dtest=AdminUserDisableEnableTest
```

Expected: test failure because `/disable` and `/enable` endpoints do not exist.

- [ ] **Step 3: Extend admin user service with disable/enable**

Modify `file-keeper/server/src/main/java/com/superprogrammer/admin/service/AdminUserService.java` to add constructor dependency:

```java
    private final RefreshTokenService refreshTokenService;
```

Add import:

```java
import com.superprogrammer.security.RefreshTokenService;
```

Add methods:

```java
    public UserSummary disable(Long adminUserId, Long userId, String note) {
        User user = userRepository.requireById(userId);
        if (AuthConstants.ROLE_SUPER_ADMIN.equals(user.getRole())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "不能禁用超级管理员");
        }
        UserSummary summary = userRepository.updateStatus(userId, AuthConstants.STATUS_DISABLED, adminUserId);
        refreshTokenService.deleteAllForUser(userId);
        auditLogService.record(adminUserId, "user.disable", "user", String.valueOf(userId), note);
        return summary;
    }

    public UserSummary enable(Long adminUserId, Long userId, String note) {
        User user = userRepository.requireById(userId);
        if (AuthConstants.ROLE_SUPER_ADMIN.equals(user.getRole())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "不能通过用户启用接口修改超级管理员");
        }
        UserSummary summary = userRepository.updateStatus(userId, AuthConstants.STATUS_ACTIVE, adminUserId);
        auditLogService.record(adminUserId, "user.enable", "user", String.valueOf(userId), note);
        return summary;
    }
```

- [ ] **Step 4: Extend admin user controller with disable/enable**

Modify `file-keeper/server/src/main/java/com/superprogrammer/admin/controller/AdminUserController.java` to add methods:

```java
    @PostMapping("/{id}/disable")
    public R<UserSummary> disable(Authentication authentication, @PathVariable Long id, @RequestBody UserReviewRequest request) {
        AuthPrincipal principal = (AuthPrincipal) authentication.getPrincipal();
        return R.ok(adminUserService.disable(principal.userId(), id, request.note()));
    }

    @PostMapping("/{id}/enable")
    public R<UserSummary> enable(Authentication authentication, @PathVariable Long id, @RequestBody UserReviewRequest request) {
        AuthPrincipal principal = (AuthPrincipal) authentication.getPrincipal();
        return R.ok(adminUserService.enable(principal.userId(), id, request.note()));
    }
```

- [ ] **Step 5: Run disable/enable tests and all tests**

Run:

```bash
JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot" MAVEN_HOME="/c/Users/19536/.local/tools/apache-maven-3.9.11" PATH="/c/Users/19536/.local/tools/apache-maven-3.9.11/bin:/c/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot/bin:$PATH" mvn -f "file-keeper/server/pom.xml" test -Dtest=AdminUserDisableEnableTest
```

Expected: `Tests run: 1, Failures: 0, Errors: 0`.

Run:

```bash
JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot" MAVEN_HOME="/c/Users/19536/.local/tools/apache-maven-3.9.11" PATH="/c/Users/19536/.local/tools/apache-maven-3.9.11/bin:/c/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot/bin:$PATH" mvn -f "file-keeper/server/pom.xml" test
```

Expected: all phase 1 and phase 2 tests pass with `BUILD SUCCESS`.

- [ ] **Step 6: Commit Task 6**

```bash
git add "file-keeper/server/src/main/java/com/superprogrammer/admin/service/AdminUserService.java" "file-keeper/server/src/main/java/com/superprogrammer/admin/controller/AdminUserController.java" "file-keeper/server/src/test/java/com/superprogrammer/admin/AdminUserDisableEnableTest.java"
git commit -m "feat: 实现用户禁用启用和令牌撤销"
```

---

## Final Verification

After Task 6, run the full service test suite:

```bash
JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot" MAVEN_HOME="/c/Users/19536/.local/tools/apache-maven-3.9.11" PATH="/c/Users/19536/.local/tools/apache-maven-3.9.11/bin:/c/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot/bin:$PATH" mvn -f "file-keeper/server/pom.xml" test
```

Expected:

- `FlywayMigrationTest` passes.
- `SuperAdminInitializerTest` passes.
- `JwtServiceTest` passes.
- `UserRegistrationTest` passes.
- `UserLoginTest` passes.
- `AdminAuthControllerTest` passes.
- `AdminUserReviewTest` passes.
- `AdminUserDisableEnableTest` passes.
- Maven output ends with `BUILD SUCCESS`.

Manual API smoke test after automated tests:

1. Start server with PostgreSQL and Redis available.
2. Configure `FILE_KEEPER_SUPER_ADMIN_EMAIL` and `FILE_KEEPER_SUPER_ADMIN_PASSWORD`.
3. Call `/api/admin/auth/login` with the bootstrap admin.
4. Call `/api/client/verification/send` and `/api/client/verification/check` for a new email.
5. Call `/api/client/auth/register` and confirm response status `pending_review`.
6. Call `/api/client/auth/login` and confirm the pending user can login.
7. Call `/api/admin/users?status=pending_review` with admin bearer token.
8. Call `/api/admin/users/{id}/approve` and confirm user status becomes `active`.
9. Call `/api/admin/users/{id}/disable` and confirm old refresh token cannot refresh.
10. Call `/api/admin/users/{id}/enable` and confirm the user can login again.

---

## Phase 2 Completion Definition

Phase 2 is complete when:

- Client can send/check verification code for email or phone.
- Client registration requires verified contact.
- Registered client user is created with `role = user` and `status = pending_review`.
- Pending-review client user can login.
- Disabled client user cannot login or refresh token.
- Client refresh token works before logout/disable.
- Client logout revokes the supplied refresh token.
- Super admin can login through admin endpoint.
- Normal users cannot login through admin endpoint.
- Admin-only user endpoints require admin bearer token.
- Admin can list users, view detail, approve pending users, disable users, and enable users.
- Approve/disable/enable operations write `admin_audit_logs`.
- Disabling a user revokes all refresh tokens for that user.
- Full Maven test suite passes.

---

## Self-Review

- Spec coverage: this plan covers verification registration, client login, admin login, user review, disable, enable, and token revocation. Module entitlements, device binding, anonymous trial, admin web, and desktop client are intentionally excluded.
- Placeholder scan: no implementation steps rely on unspecified files or undefined endpoint names.
- Type consistency: DTO names, service method names, status strings, role strings, and API paths are consistent across tasks.
- Risk check: production refresh token and verification code storage use Redis; tests use explicit test beans under the test profile.
