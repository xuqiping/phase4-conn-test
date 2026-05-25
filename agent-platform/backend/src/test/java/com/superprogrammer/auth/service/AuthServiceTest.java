// agent-platform/backend/src/test/java/com/superprogrammer/auth/service/AuthServiceTest.java
package com.superprogrammer.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.superprogrammer.auth.dto.*;
import com.superprogrammer.auth.entity.User;
import com.superprogrammer.auth.entity.UserRole;
import com.superprogrammer.auth.mapper.RoleMapper;
import com.superprogrammer.auth.mapper.UserMapper;
import com.superprogrammer.auth.mapper.UserRoleMapper;
import com.superprogrammer.auth.security.JwtUtil;
import com.superprogrammer.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserMapper userMapper;

    @Mock
    private UserRoleMapper userRoleMapper;

    @Mock
    private RoleMapper roleMapper;

    @Mock
    private JwtUtil jwtUtil;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private AuthService authService;

    private User testUser;
    private RegisterRequest registerRequest;
    private LoginRequest loginRequest;

    @BeforeEach
    void setUp() {
        testUser = new User();
        testUser.setId(1L);
        testUser.setUsername("testuser");
        testUser.setPassword("$2a$10$encoded_password");
        testUser.setEmail("test@example.com");
        testUser.setStatus("ACTIVE");
        testUser.setCreatedAt(LocalDateTime.now());
        testUser.setUpdatedAt(LocalDateTime.now());

        registerRequest = new RegisterRequest();
        registerRequest.setUsername("newuser");
        registerRequest.setPassword("password123");
        registerRequest.setEmail("new@example.com");

        loginRequest = new LoginRequest();
        loginRequest.setUsername("testuser");
        loginRequest.setPassword("password123");
    }

    @Test
    void register_success() {
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(passwordEncoder.encode("password123")).thenReturn("$2a$10$encoded");
        when(userMapper.insert(any(User.class))).thenReturn(1);
        when(userRoleMapper.insert(any(UserRole.class))).thenReturn(1);

        assertDoesNotThrow(() -> authService.register(registerRequest));

        verify(userMapper).insert(argThat(user ->
                user.getUsername().equals("newuser") &&
                user.getEmail().equals("new@example.com")
        ));
    }

    @Test
    void register_duplicateUsername_throwsException() {
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(testUser);

        assertThrows(BusinessException.class, () -> authService.register(registerRequest));
    }

    @Test
    void login_success() {
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(testUser);
        when(passwordEncoder.matches("password123", testUser.getPassword())).thenReturn(true);
        when(jwtUtil.generateAccessToken(eq(1L), eq("testuser"), anyList())).thenReturn("access-token");
        when(jwtUtil.generateRefreshToken(eq(1L))).thenReturn("refresh-token");
        when(jwtUtil.getAccessExpiration()).thenReturn(900000L);
        when(userMapper.selectRoleCodesByUsername("testuser")).thenReturn(Arrays.asList("user"));
        when(userMapper.selectPermissionCodesByUserId(1L)).thenReturn(Arrays.asList("agent:read"));
        when(userMapper.updateById(any(User.class))).thenReturn(1);

        TokenResponse response = authService.login(loginRequest);

        assertNotNull(response);
        assertEquals("access-token", response.getAccessToken());
        assertEquals("refresh-token", response.getRefreshToken());
        assertEquals("Bearer", response.getTokenType());
        assertEquals(900000L, response.getExpiresIn());
        assertNotNull(response.getUserInfo());
        assertEquals(1L, response.getUserInfo().getId());
        assertEquals("testuser", response.getUserInfo().getUsername());
    }

    @Test
    void login_wrongPassword_throwsException() {
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(testUser);
        when(passwordEncoder.matches("password123", testUser.getPassword())).thenReturn(false);

        assertThrows(BusinessException.class, () -> authService.login(loginRequest));
    }

    @Test
    void login_userNotFound_throwsException() {
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        assertThrows(BusinessException.class, () -> authService.login(loginRequest));
    }

    @Test
    void refreshToken_success() {
        RefreshTokenRequest request = new RefreshTokenRequest();
        request.setRefreshToken("valid-refresh-token");

        when(jwtUtil.isTokenValid("valid-refresh-token")).thenReturn(true);
        when(jwtUtil.getTypeFromToken("valid-refresh-token")).thenReturn("refresh");
        when(jwtUtil.getUserIdFromToken("valid-refresh-token")).thenReturn(1L);
        when(redisTemplate.hasKey(anyString())).thenReturn(false);
        when(jwtUtil.generateAccessToken(eq(1L), anyString(), anyList())).thenReturn("new-access-token");
        when(jwtUtil.getAccessExpiration()).thenReturn(900000L);
        when(userMapper.selectById(1L)).thenReturn(testUser);
        when(userMapper.selectRoleCodesByUsername("testuser")).thenReturn(Arrays.asList("user"));

        TokenResponse response = authService.refreshToken(request);

        assertNotNull(response);
        assertEquals("new-access-token", response.getAccessToken());
        assertEquals(900000L, response.getExpiresIn());
    }

    @Test
    void refreshToken_invalidToken_throwsException() {
        RefreshTokenRequest request = new RefreshTokenRequest();
        request.setRefreshToken("invalid-token");

        when(jwtUtil.isTokenValid("invalid-token")).thenReturn(false);

        assertThrows(BusinessException.class, () -> authService.refreshToken(request));
    }

    @Test
    void logout_success() {
        String accessToken = "valid-access-token";
        String refreshToken = "valid-refresh-token";

        when(jwtUtil.getTokenId(accessToken)).thenReturn("access-jti");
        when(jwtUtil.getTokenId(refreshToken)).thenReturn("refresh-jti");
        when(jwtUtil.getRemainingTtl(accessToken)).thenReturn(50000L);
        when(jwtUtil.getRemainingTtl(refreshToken)).thenReturn(3000000L);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        assertDoesNotThrow(() -> authService.logout(accessToken, refreshToken));

        verify(valueOperations, times(2)).set(anyString(), eq("1"), anyLong(), eq(TimeUnit.MILLISECONDS));
    }
}
