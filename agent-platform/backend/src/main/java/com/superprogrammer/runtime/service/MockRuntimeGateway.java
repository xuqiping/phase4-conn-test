package com.superprogrammer.runtime.service;

import com.superprogrammer.runtime.dto.ExecutionEvent;
import com.superprogrammer.runtime.dto.ExecutionRequest;
import com.superprogrammer.runtime.dto.RuntimeEdge;
import com.superprogrammer.runtime.dto.RuntimeNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;

@Service
@ConditionalOnProperty(prefix = "runtime.gateway", name = "mode", havingValue = "mock", matchIfMissing = true)
public class MockRuntimeGateway implements RuntimeGateway {

    @Override
    public Flux<ExecutionEvent> run(ExecutionRequest request) {
        List<ExecutionEvent> events = new ArrayList<>();
        events.add(event(request, null, "EXECUTION_STARTED", "RUNNING"));

        if (request.getWorkflow() != null && request.getWorkflow().getNodes() != null) {
            for (RuntimeNode node : orderedNodes(request)) {
                events.add(event(request, node.getId(), "NODE_STARTED", "RUNNING"));
                events.add(event(request, node.getId(), "NODE_COMPLETED", "SUCCESS"));
            }
        }

        events.add(event(request, null, "EXECUTION_COMPLETED", "SUCCESS"));
        return Flux.fromIterable(events);
    }

    private List<RuntimeNode> orderedNodes(ExecutionRequest request) {
        List<RuntimeNode> nodes = request.getWorkflow().getNodes();
        List<RuntimeEdge> edges = request.getWorkflow().getEdges();
        if (edges == null || edges.isEmpty()) {
            return nodes;
        }

        Map<String, RuntimeNode> nodeById = new LinkedHashMap<>();
        Map<String, Integer> indegree = new LinkedHashMap<>();
        Map<String, List<String>> outgoing = new HashMap<>();
        for (RuntimeNode node : nodes) {
            nodeById.put(node.getId(), node);
            indegree.put(node.getId(), 0);
            outgoing.put(node.getId(), new ArrayList<>());
        }

        for (RuntimeEdge edge : edges) {
            if (!nodeById.containsKey(edge.getSource()) || !nodeById.containsKey(edge.getTarget())) {
                continue;
            }
            outgoing.get(edge.getSource()).add(edge.getTarget());
            indegree.put(edge.getTarget(), indegree.get(edge.getTarget()) + 1);
        }

        Queue<String> ready = new ArrayDeque<>();
        for (Map.Entry<String, Integer> entry : indegree.entrySet()) {
            if (entry.getValue() == 0) {
                ready.add(entry.getKey());
            }
        }

        List<RuntimeNode> ordered = new ArrayList<>();
        while (!ready.isEmpty()) {
            String nodeId = ready.remove();
            ordered.add(nodeById.get(nodeId));
            for (String target : outgoing.get(nodeId)) {
                int nextIndegree = indegree.get(target) - 1;
                indegree.put(target, nextIndegree);
                if (nextIndegree == 0) {
                    ready.add(target);
                }
            }
        }

        return ordered.size() == nodes.size() ? ordered : nodes;
    }

    private ExecutionEvent event(ExecutionRequest request, String nodeId, String type, String status) {
        return ExecutionEvent.builder()
                .executionId(request.getExecutionId())
                .rootExecutionId(request.getRootExecutionId())
                .parentExecutionId(request.getParentExecutionId())
                .nodeId(nodeId)
                .type(type)
                .status(status)
                .sourceType(request.getSourceType())
                .sourceId(request.getSourceId())
                .metadata(metadata(request))
                .timestamp(OffsetDateTime.now())
                .build();
    }

    private Map<String, Object> metadata(ExecutionRequest request) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (request.getRuntime() != null) {
            metadata.putAll(request.getRuntime());
        }
        metadata.put("externalThreadId", "mock-thread-" + request.getExecutionId());
        return metadata;
    }
}
