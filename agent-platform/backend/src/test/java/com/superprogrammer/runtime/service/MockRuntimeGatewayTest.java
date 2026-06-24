package com.superprogrammer.runtime.service;

import com.superprogrammer.runtime.dto.ExecutionEvent;
import com.superprogrammer.runtime.dto.ExecutionRequest;
import com.superprogrammer.runtime.dto.RuntimeEdge;
import com.superprogrammer.runtime.dto.RuntimeNode;
import com.superprogrammer.runtime.dto.RuntimeNodeType;
import com.superprogrammer.runtime.dto.WorkflowDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MockRuntimeGatewayTest {

    @Test
    void run_emitsExecutionAndNodeEventsInStableOrder() {
        MockRuntimeGateway gateway = new MockRuntimeGateway();
        ExecutionRequest request = ExecutionRequest.builder()
                .executionId("1001")
                .rootExecutionId("1001")
                .sourceType("WORKFLOW")
                .sourceId(10L)
                .runtime(Map.of("traceId", "trace-1"))
                .workflow(WorkflowDefinition.builder()
                        .workflowId(10L)
                        .nodes(List.of(
                                node("start-1", RuntimeNodeType.START),
                                node("agent-1", RuntimeNodeType.AGENT_REF),
                                node("end-1", RuntimeNodeType.END)))
                        .build())
                .build();

        List<ExecutionEvent> events = gateway.run(request).collectList().block();

        assertThat(events).hasSize(8);
        assertThat(events).extracting(ExecutionEvent::getType)
                .containsExactly(
                        "EXECUTION_STARTED",
                        "NODE_STARTED", "NODE_COMPLETED",
                        "NODE_STARTED", "NODE_COMPLETED",
                        "NODE_STARTED", "NODE_COMPLETED",
                        "EXECUTION_COMPLETED");
        assertThat(events.get(0).getMetadata()).containsEntry("externalThreadId", "mock-thread-1001");
        assertThat(events.get(1).getNodeId()).isEqualTo("start-1");
        assertThat(events.get(3).getNodeId()).isEqualTo("agent-1");
        assertThat(events).allSatisfy(event -> {
            assertThat(event.getExecutionId()).isEqualTo("1001");
            assertThat(event.getRootExecutionId()).isEqualTo("1001");
            assertThat(event.getMetadata()).containsEntry("traceId", "trace-1");
        });
    }

    @Test
    void run_followsWorkflowEdgesInsteadOfNodeListOrder() {
        MockRuntimeGateway gateway = new MockRuntimeGateway();
        ExecutionRequest request = ExecutionRequest.builder()
                .executionId("1002")
                .rootExecutionId("1002")
                .sourceType("WORKFLOW")
                .sourceId(10L)
                .workflow(WorkflowDefinition.builder()
                        .workflowId(10L)
                        .nodes(List.of(
                                node("start-1", RuntimeNodeType.START),
                                node("end-1", RuntimeNodeType.END),
                                node("input-1", RuntimeNodeType.INPUT),
                                node("summary-1", RuntimeNodeType.AGENT_REF)))
                        .edges(List.of(
                                edge("start-1", "input-1"),
                                edge("input-1", "summary-1"),
                                edge("summary-1", "end-1")))
                        .build())
                .build();

        List<ExecutionEvent> events = gateway.run(request).collectList().block();

        assertThat(events).extracting(ExecutionEvent::getNodeId)
                .containsExactly(
                        null,
                        "start-1", "start-1",
                        "input-1", "input-1",
                        "summary-1", "summary-1",
                        "end-1", "end-1",
                        null);
    }

    private RuntimeNode node(String id, RuntimeNodeType type) {
        return RuntimeNode.builder()
                .id(id)
                .type(type)
                .label(id)
                .config(Map.of())
                .build();
    }

    private RuntimeEdge edge(String source, String target) {
        return RuntimeEdge.builder()
                .source(source)
                .target(target)
                .build();
    }
}
