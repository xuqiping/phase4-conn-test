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
                new DingTalkService.DingTalkUserInfo("uid-1", "oid-1", "nick", null, java.util.List.of());
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
