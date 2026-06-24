package com.superprogrammer.execution.controller;

import com.superprogrammer.auth.security.JwtUtil;
import com.superprogrammer.auth.entity.User;
import com.superprogrammer.auth.mapper.UserMapper;
import com.superprogrammer.execution.entity.ExecutionLog;
import com.superprogrammer.execution.service.ExecutionLogService;
import com.superprogrammer.execution.vo.ExecutionRecoveryInfoVO;
import com.superprogrammer.runtime.dto.ExecutionEvent;
import com.superprogrammer.runtime.service.RuntimeExecutionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(com.superprogrammer.common.config.TestSecurityConfig.class)
class ExecutionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RuntimeExecutionService runtimeExecutionService;

    @MockBean
    private ExecutionLogService executionLogService;

    @MockBean(name = "jwtUtil")
    private JwtUtil jwtUtil;

    @MockBean
    private UserMapper userMapper;

    @Test
    void retryExecution_returnsRuntimeEvents() throws Exception {
        when(runtimeExecutionService.retryWorkflowExecution(99L, 1L))
                .thenReturn(Flux.just(event("101", Map.of("retryOfExecutionId", "99"))));

        mockMvc.perform(post("/api/executions/99/retry")
                        .header("Authorization", "Bearer test-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].executionId").value("101"))
                .andExpect(jsonPath("$.data[0].metadata.retryOfExecutionId").value("99"));

        verify(runtimeExecutionService).retryWorkflowExecution(eq(99L), eq(1L));
    }

    @Test
    void resumeExecution_returnsRuntimeEvents() throws Exception {
        when(runtimeExecutionService.resumeWorkflowFromCheckpoint("checkpoint-99", 1L))
                .thenReturn(Flux.just(event("102", Map.of("resumeFromCheckpointRef", "checkpoint-99"))));

        mockMvc.perform(post("/api/executions/resume")
                        .param("checkpointRef", "checkpoint-99")
                        .header("Authorization", "Bearer test-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].executionId").value("102"))
                .andExpect(jsonPath("$.data[0].metadata.resumeFromCheckpointRef").value("checkpoint-99"));

        verify(runtimeExecutionService).resumeWorkflowFromCheckpoint(eq("checkpoint-99"), eq(1L));
    }

    @Test
    void getRecoveryInfo_returnsRecoverableFailureDetails() throws Exception {
        when(executionLogService.getVisibleRecoveryInfo(99L, null)).thenReturn(ExecutionRecoveryInfoVO.builder()
                .executionId(99L)
                .status("FAILED")
                .failedNodeId("agent-1")
                .errorMessage("forced failure")
                .checkpointRef("checkpoint-99")
                .recoverable(true)
                .recoverySuggestion("可从 checkpoint-99 恢复执行")
                .build());

        mockMvc.perform(get("/api/executions/99/recovery")
                        .header("Authorization", "Bearer test-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.executionId").value(99))
                .andExpect(jsonPath("$.data.failedNodeId").value("agent-1"))
                .andExpect(jsonPath("$.data.errorMessage").value("forced failure"))
                .andExpect(jsonPath("$.data.checkpointRef").value("checkpoint-99"))
                .andExpect(jsonPath("$.data.recoverable").value(true))
                .andExpect(jsonPath("$.data.recoverySuggestion").value("可从 checkpoint-99 恢复执行"));

        verify(executionLogService).getVisibleRecoveryInfo(eq(99L), eq(null));
    }

    @Test
    void approveExecution_returnsRuntimeEvents() throws Exception {
        when(runtimeExecutionService.approveWorkflowExecution(99L, 1L))
                .thenReturn(Flux.just(event("105", Map.of("approvalDecision", "approved"))));

        mockMvc.perform(post("/api/executions/99/approve")
                        .header("Authorization", "Bearer test-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].executionId").value("105"))
                .andExpect(jsonPath("$.data[0].metadata.approvalDecision").value("approved"));

        verify(runtimeExecutionService).approveWorkflowExecution(eq(99L), eq(1L));
    }

    @Test
    void rejectExecution_marksExecutionRejected() throws Exception {
        mockMvc.perform(post("/api/executions/99/reject")
                        .param("reason", "not safe")
                        .header("Authorization", "Bearer test-token"))
                .andExpect(status().isOk());

        verify(executionLogService).failExecution(eq(99L), eq("人工审批拒绝: not safe"));
    }

    @Test
    void listPendingApprovals_returnsWaitingExecutions() throws Exception {
        ExecutionLog log = new ExecutionLog();
        log.setId(99L);
        log.setStatus("WAITING_APPROVAL");
        log.setNodeId("approval-1");
        log.setCheckpointRef("checkpoint-99");
        when(executionLogService.listPendingApprovals()).thenReturn(List.of(log));

        mockMvc.perform(get("/api/executions/pending-approvals")
                        .header("Authorization", "Bearer test-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(99))
                .andExpect(jsonPath("$.data[0].status").value("WAITING_APPROVAL"))
                .andExpect(jsonPath("$.data[0].nodeId").value("approval-1"));

        verify(executionLogService).listPendingApprovals();
    }

    @Test
    void listExecutions_adminReturnsAllVisibleExecutions() throws Exception {
        ExecutionLog log = new ExecutionLog();
        log.setId(99L);
        log.setWorkflowName("联调流程");
        log.setStatus("SUCCESS");
        log.setTriggeredBy(2L);
        User user = new User();
        user.setId(2L);
        user.setUsername("alice");
        when(executionLogService.listVisibleExecutions(null)).thenReturn(List.of(log));
        when(userMapper.selectById(2L)).thenReturn(user);

        mockMvc.perform(get("/api/executions")
                        .header("Authorization", "Bearer test-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(99))
                .andExpect(jsonPath("$.data[0].workflowName").value("联调流程"))
                .andExpect(jsonPath("$.data[0].triggeredBy").value(2))
                .andExpect(jsonPath("$.data[0].triggeredByUsername").value("alice"));

        verify(executionLogService).listVisibleExecutions(null);
    }

    private ExecutionEvent event(String executionId, Map<String, Object> metadata) {
        return ExecutionEvent.builder()
                .executionId(executionId)
                .rootExecutionId(executionId)
                .type("EXECUTION_COMPLETED")
                .status("SUCCESS")
                .metadata(metadata)
                .build();
    }
}
