// agent-platform/backend/src/test/java/com/superprogrammer/auth/controller/AuthControllerTest.java
package com.superprogrammer.auth.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.auth.dto.*;
import com.superprogrammer.auth.security.JwtUtil;
import com.superprogrammer.auth.service.AuthService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Arrays;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Tag("integration")
@ActiveProfiles("it")
@Import(com.superprogrammer.common.config.TestSecurityConfig.class)
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AuthService authService;

    @MockBean(name = "jwtUtil")
    private JwtUtil jwtUtil;

    @Test
    void register_success_returns201() throws Exception {
        RegisterRequest request = new RegisterRequest();
        request.setUsername("newuser");
        request.setName("新人");
        request.setPassword("password123");
        request.setEmail("new@example.com");
        request.setEmailCode("123456");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void register_withEmptyUsername_returns400() throws Exception {
        RegisterRequest request = new RegisterRequest();
        request.setUsername("");
        request.setPassword("password123");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void login_success_returns200() throws Exception {
        LoginRequest request = new LoginRequest();
        request.setUsername("admin");
        request.setPassword("admin123");

        TokenResponse response = TokenResponse.builder()
                .accessToken("test-access-token")
                .refreshToken("test-refresh-token")
                .tokenType("Bearer")
                .expiresIn(900000L)
                .userInfo(TokenResponse.UserInfo.builder()
                        .id(1L)
                        .username("admin")
                        .roles(Arrays.asList("admin"))
                        .permissions(Arrays.asList("agent:read"))
                        .build())
                .build();

        when(authService.login(any(LoginRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.accessToken").value("test-access-token"))
                .andExpect(jsonPath("$.data.refreshToken").value("test-refresh-token"))
                .andExpect(jsonPath("$.data.userInfo.username").value("admin"));
    }

    @Test
    void login_withEmptyPassword_returns400() throws Exception {
        LoginRequest request = new LoginRequest();
        request.setUsername("admin");
        request.setPassword("");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void refresh_success_returns200() throws Exception {
        RefreshTokenRequest request = new RefreshTokenRequest();
        request.setRefreshToken("valid-refresh-token");

        TokenResponse response = TokenResponse.builder()
                .accessToken("new-access-token")
                .tokenType("Bearer")
                .expiresIn(900000L)
                .build();

        when(authService.refreshToken(any(RefreshTokenRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").value("new-access-token"));
    }

    @Test
    void logout_success_returns200() throws Exception {
        mockMvc.perform(post("/api/auth/logout")
                        .header("Authorization", "Bearer test-token"))
                .andExpect(status().isOk());
    }
}
