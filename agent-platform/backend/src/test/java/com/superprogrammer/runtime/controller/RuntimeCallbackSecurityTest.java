package com.superprogrammer.runtime.controller;

import com.superprogrammer.auth.security.JwtAuthenticationFilter;
import com.superprogrammer.auth.security.JwtUtil;
import com.superprogrammer.auth.security.SecurityConfig;
import com.superprogrammer.auth.service.AuthService;
import com.superprogrammer.runtime.dto.RuntimeNodeCallbackResponse;
import com.superprogrammer.runtime.service.RuntimeNodeCallbackService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = RuntimeCallbackController.class,
        properties = "runtime.callback.token=test-token")
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class RuntimeCallbackSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RuntimeNodeCallbackService runtimeNodeCallbackService;

    @MockBean
    private JwtUtil jwtUtil;

    @MockBean
    private AuthService authService;

    @MockBean
    private com.superprogrammer.auth.service.SessionService sessionService;

    /** S5 F2：SecurityConfig 依赖回调 HMAC 模式热更读取（mock 缺省 null=DUAL，静态 token 路径仍通） */
    @MockBean
    private com.superprogrammer.system.service.SystemSettingService systemSettingService;

    @Test
    void executeNode_allowsSidecarCallbackWithoutJwt() throws Exception {
        when(runtimeNodeCallbackService.executeNode(any()))
                .thenReturn(RuntimeNodeCallbackResponse.builder()
                        .success(true)
                        .output(Map.of("text", "done"))
                        .build());

        mockMvc.perform(post("/api/runtime/callbacks/nodes/execute")
                        // 安全加固后回调端点 fail-closed：须带预共享密钥头（无 JWT，模拟 sidecar 出站）
                        .header("X-Runtime-Token", "test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "executionId": "1001",
                                  "rootExecutionId": "1001",
                                  "nodeId": "skill-1",
                                  "sourceType": "SKILL",
                                  "sourceId": 12,
                                  "userId": 1,
                                  "input": {"message": "hello"}
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.success").value(true))
                .andExpect(jsonPath("$.data.output.text").value("done"));
    }
}
