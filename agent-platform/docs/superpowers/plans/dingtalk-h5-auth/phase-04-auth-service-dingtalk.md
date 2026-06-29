# Phase 4 — AuthService.loginByDingTalk

> 总路由：[README.md](README.md) · 上一：[Phase 3](phase-03-dingtalk-service.md) · 下一：[Phase 5](phase-05-endpoint-whitelist.md)

**Goal：** `AuthService` 按 unionId 查/建用户并签 JWT；抽取 `issueTokens` 公共方法（DRY），重构既有 `login` 末尾。

**Files:**
- Modify: `backend/src/main/java/com/superprogrammer/auth/service/AuthService.java`
- Test: `backend/src/test/java/com/superprogrammer/auth/service/AuthServiceDingTalkTest.java`

**Interfaces:**
- Consumes: `DingTalkService.DingTalkUserInfo`（Phase 3）、`User.dingtalkUnionId/dingtalkOpenId/bindType`（Phase 1）、`JwtUtil`/`UserMapper`/`RoleMapper`/`UserRoleMapper`（既有）。
- Produces: `AuthService.loginByDingTalk(DingTalkUserInfo info)` → `TokenResponse`（与账密登录同结构），供 Phase 5。

---

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
import com.superprogrammer.system.service.SystemSettingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
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
                .isInstanceOf(com.superprogrammer.common.exception.BusinessException.class);
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd backend && mvn -q -Dtest=AuthServiceDingTalkTest test`
Expected: FAIL —— `loginByDingTalk` 方法不存在，编译错误。

- [ ] **Step 3: 改 `AuthService.java`**

3a. 顶部加 import（紧接现有 import 块尾部）：

```java
import com.superprogrammer.auth.dingtalk.service.DingTalkService;
```

3b. 抽取发 token 公共方法（DRY）。在类内任意方法之间加：

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

3c. 把 `login` 方法末尾 `// 生成JWT Token ... return ... .build();` 整段替换为：

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
            // 已绑定 → 刷新 openId/avatar（防钉钉头像变更）
            if (info.openId() != null && !info.openId().equals(user.getDingtalkOpenId())) {
                user.setDingtalkOpenId(info.openId());
            }
            if (info.avatar() != null && !info.avatar().equals(user.getAvatar())) {
                user.setAvatar(info.avatar());
            }
            if (!"ACTIVE".equals(user.getStatus())) {
                throw new BusinessException(ErrorCode.UNAUTHORIZED, "用户已被禁用或锁定");
            }
        }

        List<String> roleCodes = userMapper.selectRoleCodesByUsername(user.getUsername());
        List<String> permissionCodes = userMapper.selectPermissionCodesByUserId(user.getId());
        return issueTokens(user, roleCodes, permissionCodes);
    }
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd backend && mvn -q -Dtest=AuthServiceDingTalkTest test`
Expected: PASS，3 用例全绿。再跑既有 Auth 测试防 `login` 重构回归：

Run: `cd backend && mvn -q -Dtest='AuthService*' test`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/com/superprogrammer/auth/service/AuthService.java backend/src/test/java/com/superprogrammer/auth/service/AuthServiceDingTalkTest.java
git commit -m "feat(auth): AuthService.loginByDingTalk 按 unionId 查找/建号并签 JWT"
```

- [ ] **完成后：** 回 [README](README.md) 勾掉 Phase 4，开 Phase 5。
