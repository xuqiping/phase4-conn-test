package com.superprogrammer.runtime.controller;

import com.superprogrammer.auth.security.JwtUtil;
import com.superprogrammer.runtime.dto.RuntimeNodeCallbackRequest;
import com.superprogrammer.runtime.dto.RuntimeNodeCallbackResponse;
import com.superprogrammer.runtime.service.RuntimeNodeCallbackService;
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

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Tag("integration")
@ActiveProfiles("it")
@Import(com.superprogrammer.common.config.TestSecurityConfig.class)
class RuntimeCallbackControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RuntimeNodeCallbackService runtimeNodeCallbackService;

    @MockBean(name = "jwtUtil")
    private JwtUtil jwtUtil;

    @Test
    void executeNode_acceptsSidecarCallbackPayload() throws Exception {
        when(runtimeNodeCallbackService.executeNode(argThat(request ->
                "1001".equals(request.getExecutionId())
                        && "skill-1".equals(request.getNodeId())
                        && "SKILL".equals(request.getSourceType())
                        && Long.valueOf(12L).equals(request.getSourceId()))))
                .thenReturn(RuntimeNodeCallbackResponse.builder()
                        .success(true)
                        .selectedSkillIds(List.of(12L))
                        .stepOutputs(List.of(Map.of("stepId", 1, "output", "done")))
                        .output(Map.of("text", "done"))
                        .metadata(Map.of("traceId", "trace-1"))
                        .build());

        mockMvc.perform(post("/api/runtime/callbacks/nodes/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "executionId": "1001",
                                  "rootExecutionId": "1001",
                                  "nodeId": "skill-1",
                                  "sourceType": "SKILL",
                                  "sourceId": 12,
                                  "userId": 1,
                                  "input": {"message": "hello"},
                                  "traceId": "trace-1",
                                  "metadata": {"externalThreadId": "sidecar-thread-1001"}
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.success").value(true))
                .andExpect(jsonPath("$.data.selectedSkillIds[0]").value(12))
                .andExpect(jsonPath("$.data.stepOutputs[0].output").value("done"))
                .andExpect(jsonPath("$.data.output.text").value("done"))
                .andExpect(jsonPath("$.data.metadata.traceId").value("trace-1"));

        verify(runtimeNodeCallbackService).executeNode(argThat(request ->
                "trace-1".equals(request.getTraceId())
                        && "hello".equals(request.getInput().get("message"))));
    }
}
