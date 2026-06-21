package com.superprogrammer.runtime.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeDtoTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void workflowDefinition_roundTripsStructuredNodesAndEdges() throws Exception {
        WorkflowDefinition definition = WorkflowDefinition.builder()
                .version("2026-06-03")
                .workflowId(10L)
                .name("内容生产工作流")
                .nodes(List.of(RuntimeNode.builder()
                        .id("agent-1")
                        .type(RuntimeNodeType.AGENT_REF)
                        .label("文案 Agent")
                        .config(Map.of("agentId", 3))
                        .build()))
                .edges(List.of(RuntimeEdge.builder()
                        .source("start-1")
                        .target("agent-1")
                        .sourceHandle("next")
                        .build()))
                .build();

        String json = objectMapper.writeValueAsString(definition);
        WorkflowDefinition restored = objectMapper.readValue(json, WorkflowDefinition.class);

        assertThat(restored.getWorkflowId()).isEqualTo(10L);
        assertThat(restored.getNodes()).hasSize(1);
        assertThat(restored.getNodes().get(0).getType()).isEqualTo(RuntimeNodeType.AGENT_REF);
        assertThat(restored.getNodes().get(0).getConfig()).containsEntry("agentId", 3);
        assertThat(restored.getEdges().get(0).getSource()).isEqualTo("start-1");
    }

    @Test
    void executionEvent_roundTripsMetadataAndTimestamp() throws Exception {
        OffsetDateTime timestamp = OffsetDateTime.parse("2026-06-03T10:00:00+08:00");
        ExecutionEvent event = ExecutionEvent.builder()
                .executionId("1001")
                .rootExecutionId("1001")
                .nodeId("agent-1")
                .type("NODE_STARTED")
                .status("RUNNING")
                .sourceType("AGENT")
                .sourceId(3L)
                .input(Map.of("message", "hello"))
                .metadata(Map.of(
                        "traceId", "trace-1",
                        "externalThreadId", "mock-thread-1001"))
                .timestamp(timestamp)
                .build();

        String json = objectMapper.writeValueAsString(event);
        ExecutionEvent restored = objectMapper.readValue(json, ExecutionEvent.class);

        assertThat(restored.getExecutionId()).isEqualTo("1001");
        assertThat(restored.getTimestamp()).isEqualTo(timestamp);
        assertThat(restored.getMetadata())
                .containsEntry("traceId", "trace-1")
                .containsEntry("externalThreadId", "mock-thread-1001");
    }

    @Test
    void runtimeNodeCallback_roundTripsExecutionPayloadAndResultDetails() throws Exception {
        RuntimeNodeCallbackRequest request = RuntimeNodeCallbackRequest.builder()
                .executionId("1001")
                .rootExecutionId("1001")
                .nodeId("skill-1")
                .sourceType("SKILL")
                .sourceId(12L)
                .userId(1L)
                .input(Map.of("message", "hello"))
                .traceId("trace-1")
                .metadata(Map.of("externalThreadId", "sidecar-thread-1001"))
                .build();
        RuntimeNodeCallbackResponse response = RuntimeNodeCallbackResponse.builder()
                .success(true)
                .selectedSkillIds(List.of(12L))
                .stepOutputs(List.of(Map.of("stepId", 1, "output", "done")))
                .output(Map.of("text", "done"))
                .metadata(Map.of("traceId", "trace-1"))
                .build();

        RuntimeNodeCallbackRequest restoredRequest = objectMapper.readValue(
                objectMapper.writeValueAsString(request), RuntimeNodeCallbackRequest.class);
        RuntimeNodeCallbackResponse restoredResponse = objectMapper.readValue(
                objectMapper.writeValueAsString(response), RuntimeNodeCallbackResponse.class);

        assertThat(restoredRequest.getExecutionId()).isEqualTo("1001");
        assertThat(restoredRequest.getSourceType()).isEqualTo("SKILL");
        assertThat(restoredRequest.getInput()).containsEntry("message", "hello");
        assertThat(restoredResponse.isSuccess()).isTrue();
        assertThat(restoredResponse.getSelectedSkillIds()).containsExactly(12L);
        assertThat(restoredResponse.getOutput()).containsEntry("text", "done");
    }
}
