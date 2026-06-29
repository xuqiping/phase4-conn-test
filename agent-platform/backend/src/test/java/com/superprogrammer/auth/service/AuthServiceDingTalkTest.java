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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

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
        exist.setStatus("ACTIVE");
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
