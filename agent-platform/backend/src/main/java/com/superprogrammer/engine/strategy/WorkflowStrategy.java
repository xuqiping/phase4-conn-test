package com.superprogrammer.engine.strategy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.engine.context.ExecutionContext;
import com.superprogrammer.engine.executor.SkillExecutor;
import com.superprogrammer.execution.service.ExecutionLogService;
import com.superprogrammer.workflow.entity.WorkflowEdge;
import com.superprogrammer.workflow.entity.WorkflowNode;
import com.superprogrammer.workflow.mapper.WorkflowEdgeMapper;
import com.superprogrammer.workflow.mapper.WorkflowNodeMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowStrategy implements ExecutionStrategy {

    private final WorkflowNodeMapper nodeMapper;
    private final WorkflowEdgeMapper edgeMapper;
    private final SkillExecutor skillExecutor;
    private final ExecutionLogService executionLogService;

    @Override
    public String execute(ExecutionContext context, String userMessage) {
        Long workflowId = context.getWorkflowId();
        log.info("Workflow执行模式, workflowId={}", workflowId);

        context.getVariableStore().set("input", userMessage);

        var executionLog = executionLogService.startExecution(workflowId, "workflow", 1L);
        context.setExecutionId(executionLog.getId());

        List<WorkflowNode> nodes = nodeMapper.selectList(
                new LambdaQueryWrapper<WorkflowNode>()
                        .eq(WorkflowNode::getWorkflowId, workflowId)
                        .eq(WorkflowNode::getDeleted, 0));
        List<WorkflowEdge> edges = edgeMapper.selectList(
                new LambdaQueryWrapper<WorkflowEdge>()
                        .eq(WorkflowEdge::getWorkflowId, workflowId));

        Map<String, List<String>> adjacency = new HashMap<>();
        for (WorkflowEdge edge : edges) {
            adjacency.computeIfAbsent(edge.getSourceNodeId(), k -> new ArrayList<>())
                    .add(edge.getTargetNodeId());
        }

        WorkflowNode startNode = nodes.stream()
                .filter(n -> "START".equals(n.getType()))
                .findFirst()
                .orElse(null);

        if (startNode == null) {
            executionLogService.failExecution(executionLog.getId(), "找不到开始节点");
            return "工作流没有开始节点";
        }

        Map<String, WorkflowNode> nodeMap = nodes.stream()
                .collect(Collectors.toMap(WorkflowNode::getNodeId, n -> n));

        String currentId = startNode.getNodeId();
        StringBuilder result = new StringBuilder();

        while (currentId != null) {
            WorkflowNode node = nodeMap.get(currentId);
            if (node == null) break;

            log.info("执行节点: {} ({})", node.getLabel(), node.getType());

            if ("END".equals(node.getType())) {
                break;
            }

            if ("AGENT".equals(node.getType())) {
                String configJson = node.getConfig();
                if (configJson != null && !configJson.isBlank()) {
                    try {
                        var configNode = new ObjectMapper().readTree(configJson);
                        long skillId = configNode.at("/skillId").asLong(0);
                        if (skillId > 0) {
                            String output = skillExecutor.executeSkill(skillId, context);
                            result.append(output).append("\n");
                        }
                    } catch (Exception e) {
                        log.error("解析节点配置失败", e);
                    }
                }
            }

            List<String> nextIds = adjacency.getOrDefault(currentId, List.of());
            currentId = nextIds.isEmpty() ? null : nextIds.get(0);
        }

        executionLogService.finishExecution(executionLog.getId(), result.toString());
        return result.toString().trim();
    }
}
