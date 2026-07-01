package com.superprogrammer.runtime.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.runtime.dto.RuntimeEdge;
import com.superprogrammer.runtime.dto.RuntimeNode;
import com.superprogrammer.runtime.dto.RuntimeNodeType;
import com.superprogrammer.runtime.dto.WorkflowDefinition;
import com.superprogrammer.workflow.dto.WorkflowDetailVO;
import com.superprogrammer.workflow.dto.WorkflowEdgeDTO;
import com.superprogrammer.workflow.dto.WorkflowNodeDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class WorkflowDefinitionAssembler {

    private static final String DEFINITION_VERSION = "2026-06-03";

    private final ObjectMapper objectMapper;

    public WorkflowDefinition assemble(WorkflowDetailVO workflow) {
        return WorkflowDefinition.builder()
                .version(DEFINITION_VERSION)
                .workflowId(workflow.getId())
                .name(workflow.getName())
                .nodes(toRuntimeNodes(workflow.getId(), workflow.getNodes()))
                .edges(toRuntimeEdges(workflow.getEdges()))
                .build();
    }

    private List<RuntimeNode> toRuntimeNodes(Long workflowId, List<WorkflowNodeDTO> nodes) {
        if (nodes == null) {
            return Collections.emptyList();
        }
        return nodes.stream()
                .map(node -> toRuntimeNode(workflowId, node))
                .toList();
    }

    private RuntimeNode toRuntimeNode(Long workflowId, WorkflowNodeDTO node) {
        RuntimeNodeType type = parseNodeType(node.getType());
        Map<String, Object> config = parseConfig(node.getConfig());
        validateReferenceConfig(workflowId, type, config);
        return RuntimeNode.builder()
                .id(node.getNodeId())
                .type(type)
                .label(node.getLabel())
                .config(config)
                .build();
    }

    private List<RuntimeEdge> toRuntimeEdges(List<WorkflowEdgeDTO> edges) {
        if (edges == null) {
            return Collections.emptyList();
        }
        return edges.stream()
                .map(edge -> RuntimeEdge.builder()
                        .source(edge.getSourceNodeId())
                        .target(edge.getTargetNodeId())
                        .sourceHandle(edge.getSourceHandle())
                        .targetHandle(edge.getTargetHandle())
                        .label(edge.getLabel())
                        .condition(edge.getCondition())
                        .build())
                .toList();
    }

    private RuntimeNodeType parseNodeType(String rawType) {
        if (rawType == null || rawType.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "工作流节点类型不能为空");
        }
        try {
            return RuntimeNodeType.valueOf(rawType.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "不支持的工作流节点类型: " + rawType);
        }
    }

    private Map<String, Object> parseConfig(String config) {
        if (config == null || config.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(config, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "工作流节点配置不是合法 JSON");
        }
    }

    private void validateReferenceConfig(Long workflowId, RuntimeNodeType type, Map<String, Object> config) {
        if (type == RuntimeNodeType.AGENT_REF && !config.containsKey("agentId")) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "AGENT_REF 节点必须配置 agentId");
        }
        if (type == RuntimeNodeType.WORKFLOW_REF && !config.containsKey("workflowId")) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "WORKFLOW_REF 节点必须配置 workflowId");
        }
        if (type == RuntimeNodeType.WORKFLOW_REF
                && workflowId != null
                && workflowId.equals(asLong(config.get("workflowId")))) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "WORKFLOW_REF 节点不能引用当前工作流");
        }
        if (type == RuntimeNodeType.RETRIEVAL
                && !config.containsKey("kbId") && !config.containsKey("kbIds")) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "RETRIEVAL 节点必须配置 kbId 或 kbIds");
        }
        if (type == RuntimeNodeType.HUMAN_INPUT && !config.containsKey("inputKey")) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "HUMAN_INPUT 节点必须配置 inputKey");
        }
    }

    private Long asLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
