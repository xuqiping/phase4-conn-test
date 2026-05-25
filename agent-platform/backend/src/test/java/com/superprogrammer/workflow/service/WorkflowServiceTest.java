// agent-platform/backend/src/test/java/com/superprogrammer/workflow/service/WorkflowServiceTest.java
package com.superprogrammer.workflow.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.workflow.dto.*;
import com.superprogrammer.workflow.entity.Workflow;
import com.superprogrammer.workflow.entity.WorkflowEdge;
import com.superprogrammer.workflow.entity.WorkflowNode;
import com.superprogrammer.workflow.mapper.WorkflowEdgeMapper;
import com.superprogrammer.workflow.mapper.WorkflowMapper;
import com.superprogrammer.workflow.mapper.WorkflowNodeMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WorkflowServiceTest {

    @Mock
    private WorkflowMapper workflowMapper;

    @Mock
    private WorkflowNodeMapper workflowNodeMapper;

    @Mock
    private WorkflowEdgeMapper workflowEdgeMapper;

    @InjectMocks
    private WorkflowService workflowService;

    private Workflow testWorkflow;

    @BeforeEach
    void setUp() {
        testWorkflow = new Workflow();
        testWorkflow.setId(1L);
        testWorkflow.setName("测试工作流");
        testWorkflow.setDescription("用于测试的工作流");
        testWorkflow.setStatus("DRAFT");
        testWorkflow.setOwnerId(1L);
        testWorkflow.setCreatedAt(LocalDateTime.now());
        testWorkflow.setUpdatedAt(LocalDateTime.now());
    }

    @Test
    void listWorkflows_returnsUserWorkflows() {
        when(workflowMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Arrays.asList(testWorkflow));

        List<WorkflowVO> result = workflowService.listWorkflows(1L);

        assertEquals(1, result.size());
        assertEquals("测试工作流", result.get(0).getName());
        assertEquals("DRAFT", result.get(0).getStatus());
    }

    @Test
    void getWorkflowDetail_success() {
        WorkflowNode startNode = new WorkflowNode();
        startNode.setId(1L);
        startNode.setWorkflowId(1L);
        startNode.setNodeId("node-start");
        startNode.setType("START");
        startNode.setPositionX(100.0);
        startNode.setPositionY(100.0);
        startNode.setLabel("开始");

        WorkflowNode endNode = new WorkflowNode();
        endNode.setId(2L);
        endNode.setWorkflowId(1L);
        endNode.setNodeId("node-end");
        endNode.setType("END");
        endNode.setPositionX(500.0);
        endNode.setPositionY(100.0);
        endNode.setLabel("结束");

        WorkflowEdge edge = new WorkflowEdge();
        edge.setId(1L);
        edge.setWorkflowId(1L);
        edge.setSourceNodeId("node-start");
        edge.setTargetNodeId("node-end");

        when(workflowMapper.selectById(1L)).thenReturn(testWorkflow);
        when(workflowNodeMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Arrays.asList(startNode, endNode));
        when(workflowEdgeMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Arrays.asList(edge));

        WorkflowDetailVO result = workflowService.getWorkflowDetail(1L);

        assertEquals("测试工作流", result.getName());
        assertEquals(2, result.getNodes().size());
        assertEquals(1, result.getEdges().size());
        assertEquals("START", result.getNodes().get(0).getType());
        assertEquals("END", result.getNodes().get(1).getType());
        assertEquals("node-start", result.getEdges().get(0).getSourceNodeId());
    }

    @Test
    void getWorkflowDetail_notFound_throwsException() {
        when(workflowMapper.selectById(999L)).thenReturn(null);

        assertThrows(BusinessException.class, () -> workflowService.getWorkflowDetail(999L));
    }

    @Test
    void createWorkflow_success_autoGeneratesStartEndNodes() {
        WorkflowCreateRequest request = WorkflowCreateRequest.builder()
                .name("新建工作流")
                .description("测试创建")
                .build();

        when(workflowMapper.insert(any(Workflow.class))).thenAnswer(invocation -> {
            Workflow w = invocation.getArgument(0);
            w.setId(1L);
            return 1;
        });
        when(workflowNodeMapper.insert(any(WorkflowNode.class))).thenReturn(1);

        WorkflowVO result = workflowService.createWorkflow(request, 1L);

        assertEquals("新建工作流", result.getName());

        // 验证自动生成了开始和结束节点
        ArgumentCaptor<WorkflowNode> nodeCaptor = ArgumentCaptor.forClass(WorkflowNode.class);
        verify(workflowNodeMapper, times(2)).insert(nodeCaptor.capture());

        List<WorkflowNode> insertedNodes = nodeCaptor.getAllValues();
        assertEquals("START", insertedNodes.get(0).getType());
        assertEquals("END", insertedNodes.get(1).getType());
    }

    @Test
    void updateWorkflow_success() {
        WorkflowNodeDTO nodeDTO = WorkflowNodeDTO.builder()
                .nodeId("node-1")
                .type("AGENT")
                .positionX(200.0)
                .positionY(200.0)
                .label("Agent节点")
                .build();

        WorkflowEdgeDTO edgeDTO = WorkflowEdgeDTO.builder()
                .sourceNodeId("node-start")
                .targetNodeId("node-1")
                .build();

        WorkflowCreateRequest request = WorkflowCreateRequest.builder()
                .name("更新后的工作流")
                .description("更新描述")
                .nodes(Arrays.asList(nodeDTO))
                .edges(Arrays.asList(edgeDTO))
                .build();

        when(workflowMapper.selectById(1L)).thenReturn(testWorkflow);
        when(workflowMapper.updateById(any(Workflow.class))).thenReturn(1);
        // 先删除旧节点和边
        when(workflowNodeMapper.delete(any(LambdaQueryWrapper.class))).thenReturn(2);
        when(workflowEdgeMapper.delete(any(LambdaQueryWrapper.class))).thenReturn(1);
        when(workflowNodeMapper.insert(any(WorkflowNode.class))).thenReturn(1);
        when(workflowEdgeMapper.insert(any(WorkflowEdge.class))).thenReturn(1);

        WorkflowVO result = workflowService.updateWorkflow(1L, request, 1L);

        assertEquals("更新后的工作流", result.getName());
        verify(workflowNodeMapper).delete(any(LambdaQueryWrapper.class));
        verify(workflowEdgeMapper).delete(any(LambdaQueryWrapper.class));
        verify(workflowNodeMapper).insert(any(WorkflowNode.class));
        verify(workflowEdgeMapper).insert(any(WorkflowEdge.class));
    }

    @Test
    void updateWorkflow_notFound_throwsException() {
        when(workflowMapper.selectById(999L)).thenReturn(null);

        WorkflowCreateRequest request = WorkflowCreateRequest.builder()
                .name("test")
                .build();

        assertThrows(BusinessException.class,
                () -> workflowService.updateWorkflow(999L, request, 1L));
    }

    @Test
    void deleteWorkflow_success() {
        when(workflowMapper.selectById(1L)).thenReturn(testWorkflow);
        when(workflowMapper.deleteById(1L)).thenReturn(1);

        assertDoesNotThrow(() -> workflowService.deleteWorkflow(1L, 1L));
        verify(workflowMapper).deleteById(1L);
    }

    @Test
    void deleteWorkflow_notOwner_throwsException() {
        when(workflowMapper.selectById(1L)).thenReturn(testWorkflow);

        assertThrows(BusinessException.class,
                () -> workflowService.deleteWorkflow(1L, 999L));
    }

    @Test
    void duplicateWorkflow_success() {
        when(workflowMapper.selectById(1L)).thenReturn(testWorkflow);
        when(workflowMapper.insert(any(Workflow.class))).thenAnswer(invocation -> {
            Workflow w = invocation.getArgument(0);
            w.setId(2L);
            return 1;
        });
        when(workflowNodeMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Collections.emptyList());
        when(workflowEdgeMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Collections.emptyList());
        when(workflowNodeMapper.insert(any(WorkflowNode.class))).thenReturn(1);

        WorkflowVO result = workflowService.duplicateWorkflow(1L, 1L);

        assertNotNull(result);
        verify(workflowMapper).insert(any(Workflow.class));
    }
}
