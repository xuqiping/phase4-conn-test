// agent-platform/backend/src/main/java/com/superprogrammer/workflow/service/WorkflowService.java
package com.superprogrammer.workflow.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.workflow.dto.*;
import com.superprogrammer.workflow.entity.Workflow;
import com.superprogrammer.workflow.entity.WorkflowEdge;
import com.superprogrammer.workflow.entity.WorkflowNode;
import com.superprogrammer.workflow.mapper.WorkflowEdgeMapper;
import com.superprogrammer.workflow.mapper.WorkflowMapper;
import com.superprogrammer.workflow.mapper.WorkflowNodeMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowService {

    private final WorkflowMapper workflowMapper;
    private final WorkflowNodeMapper workflowNodeMapper;
    private final WorkflowEdgeMapper workflowEdgeMapper;

    /**
     * 查询当前用户的工作流列表
     */
    public List<WorkflowVO> listWorkflows(Long userId) {
        LambdaQueryWrapper<Workflow> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Workflow::getOwnerId, userId)
                .orderByDesc(Workflow::getUpdatedAt);
        List<Workflow> workflows = workflowMapper.selectList(wrapper);

        return workflows.stream()
                .map(this::toWorkflowVO)
                .collect(Collectors.toList());
    }

    /**
     * 获取工作流详情（含nodes和edges）
     */
    public WorkflowDetailVO getWorkflowDetail(Long id) {
        Workflow workflow = workflowMapper.selectById(id);
        if (workflow == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "工作流不存在");
        }

        List<WorkflowNode> nodes = getWorkflowNodes(id);
        List<WorkflowEdge> edges = getWorkflowEdges(id);

        return WorkflowDetailVO.builder()
                .id(workflow.getId())
                .name(workflow.getName())
                .description(workflow.getDescription())
                .status(workflow.getStatus())
                .ownerId(workflow.getOwnerId())
                .nodes(nodes.stream().map(this::toNodeDTO).collect(Collectors.toList()))
                .edges(edges.stream().map(this::toEdgeDTO).collect(Collectors.toList()))
                .createdAt(workflow.getCreatedAt())
                .updatedAt(workflow.getUpdatedAt())
                .build();
    }

    /**
     * 创建工作流，自动生成开始/结束节点
     */
    @Transactional
    public WorkflowVO createWorkflow(WorkflowCreateRequest request, Long userId) {
        Workflow workflow = new Workflow();
        workflow.setName(request.getName());
        workflow.setDescription(request.getDescription());
        workflow.setStatus("DRAFT");
        workflow.setOwnerId(userId);
        workflow.setCreatedBy(userId);
        workflow.setUpdatedBy(userId);
        workflowMapper.insert(workflow);

        // 自动生成开始节点
        WorkflowNode startNode = new WorkflowNode();
        startNode.setWorkflowId(workflow.getId());
        startNode.setNodeId(UUID.randomUUID().toString());
        startNode.setType("START");
        startNode.setPositionX(100.0);
        startNode.setPositionY(300.0);
        startNode.setLabel("开始");
        startNode.setCreatedBy(userId);
        startNode.setUpdatedBy(userId);
        workflowNodeMapper.insert(startNode);

        // 自动生成结束节点
        WorkflowNode endNode = new WorkflowNode();
        endNode.setWorkflowId(workflow.getId());
        endNode.setNodeId(UUID.randomUUID().toString());
        endNode.setType("END");
        endNode.setPositionX(800.0);
        endNode.setPositionY(300.0);
        endNode.setLabel("结束");
        endNode.setCreatedBy(userId);
        endNode.setUpdatedBy(userId);
        workflowNodeMapper.insert(endNode);

        // 如果请求中包含自定义节点和边，也一并保存
        if (request.getNodes() != null) {
            for (WorkflowNodeDTO nodeDTO : request.getNodes()) {
                WorkflowNode node = new WorkflowNode();
                node.setWorkflowId(workflow.getId());
                node.setNodeId(nodeDTO.getNodeId() != null ? nodeDTO.getNodeId() : UUID.randomUUID().toString());
                node.setType(nodeDTO.getType());
                node.setPositionX(nodeDTO.getPositionX());
                node.setPositionY(nodeDTO.getPositionY());
                node.setLabel(nodeDTO.getLabel());
                node.setConfig(nodeDTO.getConfig());
                node.setCreatedBy(userId);
                node.setUpdatedBy(userId);
                workflowNodeMapper.insert(node);
            }
        }

        if (request.getEdges() != null) {
            for (WorkflowEdgeDTO edgeDTO : request.getEdges()) {
                WorkflowEdge edge = new WorkflowEdge();
                edge.setWorkflowId(workflow.getId());
                edge.setSourceNodeId(edgeDTO.getSourceNodeId());
                edge.setTargetNodeId(edgeDTO.getTargetNodeId());
                edge.setSourceHandle(edgeDTO.getSourceHandle());
                edge.setTargetHandle(edgeDTO.getTargetHandle());
                edge.setLabel(edgeDTO.getLabel());
                edge.setCondition(edgeDTO.getCondition());
                edge.setCreatedBy(userId);
                edge.setUpdatedBy(userId);
                workflowEdgeMapper.insert(edge);
            }
        }

        log.info("工作流创建成功: id={}, name={}", workflow.getId(), workflow.getName());
        return toWorkflowVO(workflow);
    }

    /**
     * 更新工作流（全量替换nodes和edges）
     */
    @Transactional
    public WorkflowVO updateWorkflow(Long id, WorkflowCreateRequest request, Long userId) {
        Workflow workflow = workflowMapper.selectById(id);
        if (workflow == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "工作流不存在");
        }

        // 更新基本信息
        workflow.setName(request.getName());
        workflow.setDescription(request.getDescription());
        workflow.setUpdatedBy(userId);
        workflowMapper.updateById(workflow);

        // 删除旧的节点和边
        LambdaQueryWrapper<WorkflowNode> nodeDeleteWrapper = new LambdaQueryWrapper<>();
        nodeDeleteWrapper.eq(WorkflowNode::getWorkflowId, id);
        workflowNodeMapper.delete(nodeDeleteWrapper);

        LambdaQueryWrapper<WorkflowEdge> edgeDeleteWrapper = new LambdaQueryWrapper<>();
        edgeDeleteWrapper.eq(WorkflowEdge::getWorkflowId, id);
        workflowEdgeMapper.delete(edgeDeleteWrapper);

        // 重新插入节点
        if (request.getNodes() != null) {
            for (WorkflowNodeDTO nodeDTO : request.getNodes()) {
                WorkflowNode node = new WorkflowNode();
                node.setWorkflowId(id);
                node.setNodeId(nodeDTO.getNodeId());
                node.setType(nodeDTO.getType());
                node.setPositionX(nodeDTO.getPositionX());
                node.setPositionY(nodeDTO.getPositionY());
                node.setLabel(nodeDTO.getLabel());
                node.setConfig(nodeDTO.getConfig());
                node.setCreatedBy(userId);
                node.setUpdatedBy(userId);
                workflowNodeMapper.insert(node);
            }
        }

        // 重新插入边
        if (request.getEdges() != null) {
            for (WorkflowEdgeDTO edgeDTO : request.getEdges()) {
                WorkflowEdge edge = new WorkflowEdge();
                edge.setWorkflowId(id);
                edge.setSourceNodeId(edgeDTO.getSourceNodeId());
                edge.setTargetNodeId(edgeDTO.getTargetNodeId());
                edge.setSourceHandle(edgeDTO.getSourceHandle());
                edge.setTargetHandle(edgeDTO.getTargetHandle());
                edge.setLabel(edgeDTO.getLabel());
                edge.setCondition(edgeDTO.getCondition());
                edge.setCreatedBy(userId);
                edge.setUpdatedBy(userId);
                workflowEdgeMapper.insert(edge);
            }
        }

        log.info("工作流更新成功: id={}", id);
        return toWorkflowVO(workflow);
    }

    /**
     * 删除工作流（逻辑删除）
     */
    public void deleteWorkflow(Long id, Long userId) {
        Workflow workflow = workflowMapper.selectById(id);
        if (workflow == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "工作流不存在");
        }

        // 检查是否是工作流拥有者
        if (!workflow.getOwnerId().equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只能删除自己创建的工作流");
        }

        workflowMapper.deleteById(id);
        log.info("工作流删除成功: id={}", id);
    }

    /**
     * 复制工作流
     */
    @Transactional
    public WorkflowVO duplicateWorkflow(Long id, Long userId) {
        Workflow source = workflowMapper.selectById(id);
        if (source == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "源工作流不存在");
        }

        // 创建新工作流
        Workflow newWorkflow = new Workflow();
        newWorkflow.setName(source.getName() + " (副本)");
        newWorkflow.setDescription(source.getDescription());
        newWorkflow.setStatus("DRAFT");
        newWorkflow.setOwnerId(userId);
        newWorkflow.setCreatedBy(userId);
        newWorkflow.setUpdatedBy(userId);
        workflowMapper.insert(newWorkflow);

        // 复制节点
        List<WorkflowNode> sourceNodes = getWorkflowNodes(id);
        for (WorkflowNode sourceNode : sourceNodes) {
            WorkflowNode newNode = new WorkflowNode();
            newNode.setWorkflowId(newWorkflow.getId());
            newNode.setNodeId(sourceNode.getNodeId());
            newNode.setType(sourceNode.getType());
            newNode.setPositionX(sourceNode.getPositionX());
            newNode.setPositionY(sourceNode.getPositionY());
            newNode.setLabel(sourceNode.getLabel());
            newNode.setConfig(sourceNode.getConfig());
            newNode.setCreatedBy(userId);
            newNode.setUpdatedBy(userId);
            workflowNodeMapper.insert(newNode);
        }

        // 复制边
        List<WorkflowEdge> sourceEdges = getWorkflowEdges(id);
        for (WorkflowEdge sourceEdge : sourceEdges) {
            WorkflowEdge newEdge = new WorkflowEdge();
            newEdge.setWorkflowId(newWorkflow.getId());
            newEdge.setSourceNodeId(sourceEdge.getSourceNodeId());
            newEdge.setTargetNodeId(sourceEdge.getTargetNodeId());
            newEdge.setSourceHandle(sourceEdge.getSourceHandle());
            newEdge.setTargetHandle(sourceEdge.getTargetHandle());
            newEdge.setLabel(sourceEdge.getLabel());
            newEdge.setCondition(sourceEdge.getCondition());
            newEdge.setCreatedBy(userId);
            newEdge.setUpdatedBy(userId);
            workflowEdgeMapper.insert(newEdge);
        }

        log.info("工作流复制成功: sourceId={}, newId={}", id, newWorkflow.getId());
        return toWorkflowVO(newWorkflow);
    }

    // ==================== 私有方法 ====================

    private List<WorkflowNode> getWorkflowNodes(Long workflowId) {
        LambdaQueryWrapper<WorkflowNode> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(WorkflowNode::getWorkflowId, workflowId);
        return workflowNodeMapper.selectList(wrapper);
    }

    private List<WorkflowEdge> getWorkflowEdges(Long workflowId) {
        LambdaQueryWrapper<WorkflowEdge> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(WorkflowEdge::getWorkflowId, workflowId);
        return workflowEdgeMapper.selectList(wrapper);
    }

    private WorkflowVO toWorkflowVO(Workflow workflow) {
        return WorkflowVO.builder()
                .id(workflow.getId())
                .name(workflow.getName())
                .description(workflow.getDescription())
                .status(workflow.getStatus())
                .ownerId(workflow.getOwnerId())
                .createdAt(workflow.getCreatedAt())
                .updatedAt(workflow.getUpdatedAt())
                .build();
    }

    private WorkflowNodeDTO toNodeDTO(WorkflowNode node) {
        return WorkflowNodeDTO.builder()
                .id(node.getId())
                .nodeId(node.getNodeId())
                .type(node.getType())
                .positionX(node.getPositionX())
                .positionY(node.getPositionY())
                .label(node.getLabel())
                .config(node.getConfig())
                .build();
    }

    private WorkflowEdgeDTO toEdgeDTO(WorkflowEdge edge) {
        return WorkflowEdgeDTO.builder()
                .id(edge.getId())
                .sourceNodeId(edge.getSourceNodeId())
                .targetNodeId(edge.getTargetNodeId())
                .sourceHandle(edge.getSourceHandle())
                .targetHandle(edge.getTargetHandle())
                .label(edge.getLabel())
                .condition(edge.getCondition())
                .build();
    }
}
