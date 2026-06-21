package com.superprogrammer.runtime.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.execution.entity.ExecutionLog;
import com.superprogrammer.execution.service.ExecutionLogService;
import com.superprogrammer.runtime.config.RuntimeGatewayProperties;
import com.superprogrammer.runtime.dto.ExecutionEvent;
import com.superprogrammer.runtime.dto.WorkflowDefinition;
import com.superprogrammer.workflow.dto.WorkflowDetailVO;
import com.superprogrammer.workflow.dto.WorkflowNodeDTO;
import com.superprogrammer.workflow.service.WorkflowService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RuntimeExecutionServiceTest {

    @Mock
    private WorkflowService workflowService;

    @Mock
    private RuntimeGateway runtimeGateway;

    @Mock
    private ExecutionLogService executionLogService;

    @Test
    void runWorkflow_buildsExecutionRequestAndPersistsEvents() {
        WorkflowDetailVO workflow = WorkflowDetailVO.builder()
                .id(10L)
                .name("组合流程")
                .nodes(List.of(WorkflowNodeDTO.builder()
                        .nodeId("start-1")
                        .type("START")
                        .label("开始")
                        .build()))
                .build();
        ExecutionLog executionLog = new ExecutionLog();
        executionLog.setId(100L);
        executionLog.setRootExecutionId(100L);

        when(workflowService.getWorkflowDetail(10L)).thenReturn(workflow);
        when(executionLogService.startRuntimeExecution(
                eq(10L), eq("组合流程"), eq(7L), eq("WORKFLOW"), eq(10L), eq(null), eq(null), any()))
                .thenReturn(executionLog);
        when(runtimeGateway.run(any())).thenAnswer(invocation -> {
            com.superprogrammer.runtime.dto.ExecutionRequest request = invocation.getArgument(0);
            return Flux.just(
                    ExecutionEvent.builder()
                            .executionId(request.getExecutionId())
                            .rootExecutionId(request.getRootExecutionId())
                            .type("EXECUTION_STARTED")
                            .status("RUNNING")
                            .metadata(Map.of("externalThreadId", "mock-thread-100"))
                            .build(),
                    ExecutionEvent.builder()
                            .executionId(request.getExecutionId())
                            .rootExecutionId(request.getRootExecutionId())
                            .nodeId("start-1")
                            .type("NODE_COMPLETED")
                            .status("SUCCESS")
                            .metadata(Map.of("externalThreadId", "mock-thread-100", "checkpointRef", "checkpoint-1"))
                            .build(),
                    ExecutionEvent.builder()
                            .executionId(request.getExecutionId())
                            .rootExecutionId(request.getRootExecutionId())
                            .type("EXECUTION_COMPLETED")
                            .status("SUCCESS")
                            .metadata(Map.of("externalThreadId", "mock-thread-100", "checkpointRef", "checkpoint-1"))
                            .build());
        });

        RuntimeExecutionService service = new RuntimeExecutionService(
                workflowService,
                new WorkflowDefinitionAssembler(new ObjectMapper()),
                runtimeGateway,
                executionLogService,
                new ObjectMapper(),
                runtimeGatewayProperties());

        List<ExecutionEvent> events = service.runWorkflow(10L, 7L, Map.of("message", "hello"))
                .collectList()
                .block();

        assertThat(events).hasSize(3);
        ArgumentCaptor<com.superprogrammer.runtime.dto.ExecutionRequest> requestCaptor =
                ArgumentCaptor.forClass(com.superprogrammer.runtime.dto.ExecutionRequest.class);
        verify(runtimeGateway).run(requestCaptor.capture());
        assertThat(requestCaptor.getValue().getExecutionId()).isEqualTo("100");
        assertThat(requestCaptor.getValue().getRootExecutionId()).isEqualTo("100");
        assertThat(requestCaptor.getValue().getInput()).containsEntry("message", "hello");
        assertThat(requestCaptor.getValue().getRuntime()).containsEntry("javaCallbackBaseUrl", "http://java-callback:8080");
        WorkflowDefinition definition = requestCaptor.getValue().getWorkflow();
        assertThat(definition.getWorkflowId()).isEqualTo(10L);
        assertThat(definition.getNodes()).hasSize(1);

        verify(executionLogService, times(3)).appendRuntimeEventSnapshot(eq(100L), any());
        verify(executionLogService, atLeastOnce()).updateRuntimeRefs(100L, "mock-thread-100", "checkpoint-1");
        verify(executionLogService).finishExecution(eq(100L), any());
    }

    @Test
    void runWorkflowFromChat_overridesStartInputKeyWithChatMessage() {
        WorkflowDetailVO workflow = WorkflowDetailVO.builder()
                .id(10L)
                .name("chat-flow")
                .nodes(List.of(WorkflowNodeDTO.builder()
                        .nodeId("start-1")
                        .type("START")
                        .label("Start")
                        .config("{\"inputKey\":\"ccc\",\"value\":\"old-value\",\"nodeAlias\":\"node_start_1\"}")
                        .build()))
                .build();
        ExecutionLog executionLog = new ExecutionLog();
        executionLog.setId(110L);
        executionLog.setRootExecutionId(110L);

        when(workflowService.getWorkflowDetail(10L)).thenReturn(workflow);
        when(executionLogService.startRuntimeExecution(
                eq(10L), eq("chat-flow"), eq(7L), eq("WORKFLOW"), eq(10L), eq(null), eq(null), any()))
                .thenReturn(executionLog);
        when(runtimeGateway.run(any())).thenAnswer(invocation -> {
            com.superprogrammer.runtime.dto.ExecutionRequest request = invocation.getArgument(0);
            return Flux.just(ExecutionEvent.builder()
                    .executionId(request.getExecutionId())
                    .rootExecutionId(request.getRootExecutionId())
                    .type("EXECUTION_COMPLETED")
                    .status("SUCCESS")
                    .build());
        });

        RuntimeExecutionService service = new RuntimeExecutionService(
                workflowService,
                new WorkflowDefinitionAssembler(new ObjectMapper()),
                runtimeGateway,
                executionLogService,
                new ObjectMapper(),
                runtimeGatewayProperties());

        service.runWorkflowFromChat(10L, 7L, "你好啊").collectList().block();

        ArgumentCaptor<com.superprogrammer.runtime.dto.ExecutionRequest> requestCaptor =
                ArgumentCaptor.forClass(com.superprogrammer.runtime.dto.ExecutionRequest.class);
        verify(runtimeGateway).run(requestCaptor.capture());
        assertThat(requestCaptor.getValue().getInput())
                .containsEntry("input", "你好啊")
                .containsEntry("message", "你好啊")
                .containsEntry("prompt", "你好啊")
                .containsEntry("text", "你好啊")
                .containsEntry("ccc", "你好啊");
    }

    @Test
    void retryWorkflowExecution_reusesWorkflowIdFromPreviousExecution() {
        WorkflowDetailVO workflow = WorkflowDetailVO.builder()
                .id(10L)
                .name("retry-flow")
                .nodes(List.of(WorkflowNodeDTO.builder()
                        .nodeId("start-1")
                        .type("START")
                        .label("Start")
                        .build()))
                .build();
        ExecutionLog previous = new ExecutionLog();
        previous.setId(99L);
        previous.setWorkflowId(10L);
        ExecutionLog retryLog = new ExecutionLog();
        retryLog.setId(101L);
        retryLog.setRootExecutionId(101L);

        when(executionLogService.getExecutionLog(99L)).thenReturn(previous);
        when(workflowService.getWorkflowDetail(10L)).thenReturn(workflow);
        when(executionLogService.startRuntimeExecution(
                eq(10L), eq("retry-flow"), eq(7L), eq("WORKFLOW"), eq(10L), eq(null), eq(null), any()))
                .thenReturn(retryLog);
        when(runtimeGateway.run(any())).thenAnswer(invocation -> {
            com.superprogrammer.runtime.dto.ExecutionRequest request = invocation.getArgument(0);
            return Flux.just(ExecutionEvent.builder()
                    .executionId(request.getExecutionId())
                    .rootExecutionId(request.getRootExecutionId())
                    .type("EXECUTION_COMPLETED")
                    .status("SUCCESS")
                    .metadata(Map.of("externalThreadId", "mock-thread-101", "checkpointRef", "checkpoint-101"))
                    .build());
        });

        RuntimeExecutionService service = new RuntimeExecutionService(
                workflowService,
                new WorkflowDefinitionAssembler(new ObjectMapper()),
                runtimeGateway,
                executionLogService,
                new ObjectMapper(),
                runtimeGatewayProperties());

        List<ExecutionEvent> events = service.retryWorkflowExecution(99L, 7L).collectList().block();

        assertThat(events).hasSize(1);
        ArgumentCaptor<com.superprogrammer.runtime.dto.ExecutionRequest> requestCaptor =
                ArgumentCaptor.forClass(com.superprogrammer.runtime.dto.ExecutionRequest.class);
        verify(runtimeGateway).run(requestCaptor.capture());
        assertThat(requestCaptor.getValue().getExecutionId()).isEqualTo("101");
        assertThat(requestCaptor.getValue().getRootExecutionId()).isEqualTo("101");
        assertThat(requestCaptor.getValue().getWorkflow().getWorkflowId()).isEqualTo(10L);
        assertThat(requestCaptor.getValue().getRuntime()).containsEntry("retryOfExecutionId", "99");
        verify(executionLogService).finishExecution(eq(101L), any());
    }

    @Test
    void resumeWorkflowFromCheckpoint_buildsRequestWithCheckpointRef() {
        WorkflowDetailVO workflow = WorkflowDetailVO.builder()
                .id(10L)
                .name("resume-flow")
                .nodes(List.of(WorkflowNodeDTO.builder()
                        .nodeId("start-1")
                        .type("START")
                        .label("Start")
                        .build()))
                .build();
        ExecutionLog previous = new ExecutionLog();
        previous.setId(99L);
        previous.setWorkflowId(10L);
        previous.setCheckpointRef("checkpoint-99");
        ExecutionLog resumeLog = new ExecutionLog();
        resumeLog.setId(102L);
        resumeLog.setRootExecutionId(102L);

        when(executionLogService.findByCheckpointRef("checkpoint-99")).thenReturn(previous);
        when(workflowService.getWorkflowDetail(10L)).thenReturn(workflow);
        when(executionLogService.startRuntimeExecution(
                eq(10L), eq("resume-flow"), eq(7L), eq("WORKFLOW"), eq(10L), eq(null), eq(null), any()))
                .thenReturn(resumeLog);
        when(runtimeGateway.run(any())).thenAnswer(invocation -> {
            com.superprogrammer.runtime.dto.ExecutionRequest request = invocation.getArgument(0);
            return Flux.just(ExecutionEvent.builder()
                    .executionId(request.getExecutionId())
                    .rootExecutionId(request.getRootExecutionId())
                    .type("EXECUTION_COMPLETED")
                    .status("SUCCESS")
                    .metadata(Map.of("externalThreadId", "mock-thread-102", "checkpointRef", "checkpoint-102"))
                    .build());
        });

        RuntimeExecutionService service = new RuntimeExecutionService(
                workflowService,
                new WorkflowDefinitionAssembler(new ObjectMapper()),
                runtimeGateway,
                executionLogService,
                new ObjectMapper(),
                runtimeGatewayProperties());

        List<ExecutionEvent> events = service.resumeWorkflowFromCheckpoint("checkpoint-99", 7L).collectList().block();

        assertThat(events).hasSize(1);
        ArgumentCaptor<com.superprogrammer.runtime.dto.ExecutionRequest> requestCaptor =
                ArgumentCaptor.forClass(com.superprogrammer.runtime.dto.ExecutionRequest.class);
        verify(runtimeGateway).run(requestCaptor.capture());
        assertThat(requestCaptor.getValue().getExecutionId()).isEqualTo("102");
        assertThat(requestCaptor.getValue().getRootExecutionId()).isEqualTo("102");
        assertThat(requestCaptor.getValue().getWorkflow().getWorkflowId()).isEqualTo(10L);
        assertThat(requestCaptor.getValue().getRuntime()).containsEntry("resumeFromCheckpointRef", "checkpoint-99");
        assertThat(requestCaptor.getValue().getRuntime()).containsEntry("resumeOfExecutionId", "99");
        verify(executionLogService).finishExecution(eq(102L), any());
    }

    @Test
    void runWorkflow_persistsFailureMetadataForRecovery() {
        WorkflowDetailVO workflow = WorkflowDetailVO.builder()
                .id(10L)
                .name("failure-flow")
                .nodes(List.of(WorkflowNodeDTO.builder()
                        .nodeId("agent-1")
                        .type("AGENT_REF")
                        .label("Agent")
                        .config("{\"agentId\":3}")
                        .build()))
                .build();
        ExecutionLog executionLog = new ExecutionLog();
        executionLog.setId(103L);
        executionLog.setRootExecutionId(103L);

        when(workflowService.getWorkflowDetail(10L)).thenReturn(workflow);
        when(executionLogService.startRuntimeExecution(
                eq(10L), eq("failure-flow"), eq(7L), eq("WORKFLOW"), eq(10L), eq(null), eq(null), any()))
                .thenReturn(executionLog);
        when(runtimeGateway.run(any())).thenAnswer(invocation -> {
            com.superprogrammer.runtime.dto.ExecutionRequest request = invocation.getArgument(0);
            return Flux.just(ExecutionEvent.builder()
                    .executionId(request.getExecutionId())
                    .rootExecutionId(request.getRootExecutionId())
                    .nodeId("agent-1")
                    .type("EXECUTION_FAILED")
                    .status("FAILED")
                    .metadata(Map.of(
                            "externalThreadId", "mock-thread-103",
                            "failedNodeId", "agent-1",
                            "errorMessage", "forced failure",
                            "recoveryCheckpointRef", "checkpoint-103"))
                    .build());
        });

        RuntimeExecutionService service = new RuntimeExecutionService(
                workflowService,
                new WorkflowDefinitionAssembler(new ObjectMapper()),
                runtimeGateway,
                executionLogService,
                new ObjectMapper(),
                runtimeGatewayProperties());

        List<ExecutionEvent> events = service.runWorkflow(10L, 7L, Map.of()).collectList().block();

        assertThat(events).hasSize(1);
        verify(executionLogService).updateRuntimeRefs(103L, "mock-thread-103", "checkpoint-103");
        verify(executionLogService).failExecution(103L, "节点 agent-1 执行失败: forced failure");
    }

    @Test
    void runWorkflow_persistsWaitingApprovalMetadata() {
        WorkflowDetailVO workflow = WorkflowDetailVO.builder()
                .id(10L)
                .name("approval-flow")
                .nodes(List.of(WorkflowNodeDTO.builder()
                        .nodeId("approval-1")
                        .type("HUMAN_APPROVAL")
                        .label("Approval")
                        .config("{\"approvalKey\":\"deploy-prod\"}")
                        .build()))
                .build();
        ExecutionLog executionLog = new ExecutionLog();
        executionLog.setId(104L);
        executionLog.setRootExecutionId(104L);

        when(workflowService.getWorkflowDetail(10L)).thenReturn(workflow);
        when(executionLogService.startRuntimeExecution(
                eq(10L), eq("approval-flow"), eq(7L), eq("WORKFLOW"), eq(10L), eq(null), eq(null), any()))
                .thenReturn(executionLog);
        when(runtimeGateway.run(any())).thenAnswer(invocation -> {
            com.superprogrammer.runtime.dto.ExecutionRequest request = invocation.getArgument(0);
            return Flux.just(ExecutionEvent.builder()
                    .executionId(request.getExecutionId())
                    .rootExecutionId(request.getRootExecutionId())
                    .nodeId("approval-1")
                    .type("WAITING_APPROVAL")
                    .status("WAITING_APPROVAL")
                    .metadata(Map.of(
                            "externalThreadId", "mock-thread-104",
                            "approvalKey", "deploy-prod",
                            "approvalCheckpointRef", "checkpoint-104"))
                    .build());
        });

        RuntimeExecutionService service = new RuntimeExecutionService(
                workflowService,
                new WorkflowDefinitionAssembler(new ObjectMapper()),
                runtimeGateway,
                executionLogService,
                new ObjectMapper(),
                runtimeGatewayProperties());

        List<ExecutionEvent> events = service.runWorkflow(10L, 7L, Map.of()).collectList().block();

        assertThat(events).hasSize(1);
        verify(executionLogService).updateRuntimeRefs(104L, "mock-thread-104", "checkpoint-104");
        verify(executionLogService).waitForApproval(104L, "approval-1", "deploy-prod");
    }

    @Test
    void runWorkflow_persistsNodeOutputInRuntimeEventSnapshot() {
        WorkflowDetailVO workflow = WorkflowDetailVO.builder()
                .id(10L)
                .name("agent-output-flow")
                .nodes(List.of(WorkflowNodeDTO.builder()
                        .nodeId("agent-1")
                        .type("AGENT_REF")
                        .label("Agent")
                        .config("{\"agentId\":3}")
                        .build()))
                .build();
        ExecutionLog executionLog = new ExecutionLog();
        executionLog.setId(106L);
        executionLog.setRootExecutionId(106L);

        when(workflowService.getWorkflowDetail(10L)).thenReturn(workflow);
        when(executionLogService.startRuntimeExecution(
                eq(10L), eq("agent-output-flow"), eq(7L), eq("WORKFLOW"), eq(10L), eq(null), eq(null), any()))
                .thenReturn(executionLog);
        when(runtimeGateway.run(any())).thenAnswer(invocation -> {
            com.superprogrammer.runtime.dto.ExecutionRequest request = invocation.getArgument(0);
            return Flux.just(ExecutionEvent.builder()
                    .executionId(request.getExecutionId())
                    .rootExecutionId(request.getRootExecutionId())
                    .nodeId("agent-1")
                    .type("NODE_COMPLETED")
                    .status("SUCCESS")
                    .sourceType("AGENT")
                    .sourceId(3L)
                    .output(Map.of(
                            "text", "final docs",
                            "agentName", "writer",
                            "selectedSkillIds", List.of(12, 13),
                            "stepOutputs", List.of(Map.of("skillId", 12, "output", "outline"))))
                    .metadata(Map.of("externalThreadId", "mock-thread-106"))
                    .build());
        });

        RuntimeExecutionService service = new RuntimeExecutionService(
                workflowService,
                new WorkflowDefinitionAssembler(new ObjectMapper()),
                runtimeGateway,
                executionLogService,
                new ObjectMapper(),
                runtimeGatewayProperties());

        service.runWorkflow(10L, 7L, Map.of()).collectList().block();

        ArgumentCaptor<Map<String, ?>> snapshotCaptor = ArgumentCaptor.forClass(Map.class);
        verify(executionLogService).appendRuntimeEventSnapshot(eq(106L), snapshotCaptor.capture());
        assertThat(snapshotCaptor.getValue()).containsKey("output");
        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) snapshotCaptor.getValue().get("output");
        assertThat(output)
                .containsEntry("text", "final docs")
                .containsEntry("agentName", "writer");
        assertThat(output.get("selectedSkillIds")).isEqualTo(List.of(12, 13));
    }

    @Test
    void approveWorkflowExecution_resumesFromApprovalCheckpoint() {
        WorkflowDetailVO workflow = WorkflowDetailVO.builder()
                .id(10L)
                .name("approval-flow")
                .nodes(List.of(WorkflowNodeDTO.builder()
                        .nodeId("approval-1")
                        .type("HUMAN_APPROVAL")
                        .label("Approval")
                        .build()))
                .build();
        ExecutionLog waiting = new ExecutionLog();
        waiting.setId(104L);
        waiting.setWorkflowId(10L);
        waiting.setCheckpointRef("checkpoint-104");
        ExecutionLog resumed = new ExecutionLog();
        resumed.setId(105L);
        resumed.setRootExecutionId(105L);

        when(executionLogService.getExecutionLog(104L)).thenReturn(waiting);
        when(workflowService.getWorkflowDetail(10L)).thenReturn(workflow);
        when(executionLogService.startRuntimeExecution(
                eq(10L), eq("approval-flow"), eq(7L), eq("WORKFLOW"), eq(10L), eq(null), eq(null), any()))
                .thenReturn(resumed);
        when(runtimeGateway.run(any())).thenAnswer(invocation -> {
            com.superprogrammer.runtime.dto.ExecutionRequest request = invocation.getArgument(0);
            return Flux.just(ExecutionEvent.builder()
                    .executionId(request.getExecutionId())
                    .rootExecutionId(request.getRootExecutionId())
                    .type("EXECUTION_COMPLETED")
                    .status("SUCCESS")
                    .metadata(Map.of("checkpointRef", "checkpoint-105"))
                    .build());
        });

        RuntimeExecutionService service = new RuntimeExecutionService(
                workflowService,
                new WorkflowDefinitionAssembler(new ObjectMapper()),
                runtimeGateway,
                executionLogService,
                new ObjectMapper(),
                runtimeGatewayProperties());

        service.approveWorkflowExecution(104L, 7L).collectList().block();

        ArgumentCaptor<com.superprogrammer.runtime.dto.ExecutionRequest> requestCaptor =
                ArgumentCaptor.forClass(com.superprogrammer.runtime.dto.ExecutionRequest.class);
        verify(runtimeGateway).run(requestCaptor.capture());
        assertThat(requestCaptor.getValue().getRuntime()).containsEntry("resumeFromCheckpointRef", "checkpoint-104");
        assertThat(requestCaptor.getValue().getRuntime()).containsEntry("approvalDecision", "approved");
    }

    private RuntimeGatewayProperties runtimeGatewayProperties() {
        RuntimeGatewayProperties properties = new RuntimeGatewayProperties();
        properties.setJavaCallbackBaseUrl("http://java-callback:8080");
        return properties;
    }
}
