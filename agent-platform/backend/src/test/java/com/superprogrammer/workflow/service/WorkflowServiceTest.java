// agent-platform/backend/src/test/java/com/superprogrammer/workflow/service/WorkflowServiceTest.java
package com.superprogrammer.workflow.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.agent.entity.Agent;
import com.superprogrammer.agent.entity.Skill;
import com.superprogrammer.agent.entity.SkillStep;
import com.superprogrammer.agent.mapper.AgentMapper;
import com.superprogrammer.agent.mapper.SkillMapper;
import com.superprogrammer.agent.mapper.SkillStepMapper;
import com.superprogrammer.agent.service.AgentPermissionService;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
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

    private WorkflowService workflowService;

    @Mock
    private AgentMapper agentMapper;

    @Mock
    private SkillMapper skillMapper;

    @Mock
    private SkillStepMapper skillStepMapper;

    @Mock
    private AgentPermissionService agentPermissionService;

    private Workflow testWorkflow;

    @BeforeEach
    void setUp() {
        workflowService = new WorkflowService(
                workflowMapper,
                workflowNodeMapper,
                workflowEdgeMapper,
                agentMapper,
                skillMapper,
                skillStepMapper,
                agentPermissionService,
                new ObjectMapper());

        testWorkflow = new Workflow();
        testWorkflow.setId(1L);
        testWorkflow.setName("测试工作流");
        testWorkflow.setDescription("用于测试的工作流");
        testWorkflow.setStatus("DRAFT");
        testWorkflow.setOwnerId(1L);
        testWorkflow.setCreatedAt(OffsetDateTime.now());
        testWorkflow.setUpdatedAt(OffsetDateTime.now());
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
    void getWorkflowDetailForUser_hidesSkillPromptFieldsFromNonAgentOwner() {
        WorkflowNode skillNode = new WorkflowNode();
        skillNode.setId(3L);
        skillNode.setWorkflowId(1L);
        skillNode.setNodeId("skill-1");
        skillNode.setType("SKILL");
        skillNode.setPositionX(100.0);
        skillNode.setPositionY(100.0);
        skillNode.setLabel("Skill");
        skillNode.setConfig("""
                {"skillId":10,"agentId":4,"systemPrompt":"secret system","promptTemplate":"secret {{input}}","model":"m","temperature":0.2,"outputKey":"summary","nodeAlias":"skillOne"}
                """);
        Agent agent = new Agent();
        agent.setId(4L);
        agent.setCreatedBy(99L);

        when(workflowMapper.selectById(1L)).thenReturn(testWorkflow);
        when(workflowNodeMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(skillNode));
        when(workflowEdgeMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        when(agentMapper.selectById(4L)).thenReturn(agent);

        WorkflowDetailVO result = workflowService.getWorkflowDetail(1L, 1L, false);

        assertFalse(result.getNodes().get(0).getConfig().contains("systemPrompt"));
        assertFalse(result.getNodes().get(0).getConfig().contains("promptTemplate"));
        assertTrue(result.getNodes().get(0).getConfig().contains("nodeAlias"));
    }

    @Test
    void getWorkflowDetailForUser_exposesPromptReadOnlyWithAgentReadPromptPermission() {
        WorkflowNode skillNode = new WorkflowNode();
        skillNode.setId(3L);
        skillNode.setWorkflowId(1L);
        skillNode.setNodeId("skill-1");
        skillNode.setType("SKILL");
        skillNode.setPositionX(100.0);
        skillNode.setPositionY(100.0);
        skillNode.setLabel("Skill");
        skillNode.setConfig("""
                {"skillId":10,"agentId":4,"systemPrompt":"visible system","promptTemplate":"visible {{input}}","nodeAlias":"skillOne"}
                """);
        Agent agent = new Agent();
        agent.setId(4L);
        agent.setCreatedBy(99L);
        Skill skill = new Skill();
        skill.setId(10L);
        skill.setAgentId(4L);
        skill.setCreatedBy(99L);

        when(workflowMapper.selectById(1L)).thenReturn(testWorkflow);
        when(workflowNodeMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(skillNode));
        when(workflowEdgeMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        when(skillMapper.selectById(10L)).thenReturn(skill);
        when(agentMapper.selectById(4L)).thenReturn(agent);
        when(agentPermissionService.canReadPrompt(4L, 1L, false)).thenReturn(true);

        WorkflowDetailVO result = workflowService.getWorkflowDetail(1L, 1L, false);

        String config = result.getNodes().get(0).getConfig();
        assertTrue(config.contains("\"systemPrompt\":\"visible system\""));
        assertTrue(config.contains("\"promptTemplate\":\"visible {{input}}\""));
        assertTrue(config.contains("\"promptConfigVisible\":true"));
        assertTrue(config.contains("\"promptConfigEditable\":false"));
    }

    @Test
    void getWorkflowDetailForUser_exposesPublicSkillInputParamsFromSkillConfig() {
        WorkflowNode skillNode = new WorkflowNode();
        skillNode.setId(3L);
        skillNode.setWorkflowId(1L);
        skillNode.setNodeId("skill-1");
        skillNode.setType("SKILL");
        skillNode.setPositionX(100.0);
        skillNode.setPositionY(100.0);
        skillNode.setLabel("Skill");
        skillNode.setConfig("""
                {"skillId":10,"agentId":4,"nodeAlias":"summaryNode","systemPrompt":"secret"}
                """);
        Agent agent = new Agent();
        agent.setId(4L);
        agent.setCreatedBy(99L);
        Skill skill = new Skill();
        skill.setId(10L);
        skill.setAgentId(4L);
        skill.setConfig("""
                {"inputParams":[{"key":"summary","label":"摘要","description":"上游联调摘要","required":true},{"key":"testResult","label":"测试结果","description":"接口测试输出","required":false}]}
                """);

        when(workflowMapper.selectById(1L)).thenReturn(testWorkflow);
        when(workflowNodeMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(skillNode));
        when(workflowEdgeMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        when(skillMapper.selectById(10L)).thenReturn(skill);
        when(agentMapper.selectById(4L)).thenReturn(agent);

        WorkflowDetailVO result = workflowService.getWorkflowDetail(1L, 1L, false);

        String config = result.getNodes().get(0).getConfig();
        assertTrue(config.contains("\"inputParams\""));
        assertTrue(config.contains("\"key\":\"summary\""));
        assertTrue(config.contains("\"description\":\"上游联调摘要\""));
        assertFalse(config.contains("systemPrompt"));
    }

    @Test
    void getWorkflowDetailForUser_exposesSkillDescriptionReadOnlyToNonCreator() {
        WorkflowNode skillNode = new WorkflowNode();
        skillNode.setId(3L);
        skillNode.setWorkflowId(1L);
        skillNode.setNodeId("skill-1");
        skillNode.setType("SKILL");
        skillNode.setPositionX(100.0);
        skillNode.setPositionY(100.0);
        skillNode.setLabel("Skill");
        skillNode.setConfig("""
                {"skillId":10,"agentId":4,"nodeAlias":"summaryNode"}
                """);
        Agent agent = new Agent();
        agent.setId(4L);
        agent.setCreatedBy(99L);
        Skill skill = new Skill();
        skill.setId(10L);
        skill.setAgentId(4L);
        skill.setCreatedBy(99L);
        skill.setDescription("Public skill description");

        when(workflowMapper.selectById(1L)).thenReturn(testWorkflow);
        when(workflowNodeMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(skillNode));
        when(workflowEdgeMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        when(skillMapper.selectById(10L)).thenReturn(skill);
        when(agentMapper.selectById(4L)).thenReturn(agent);

        WorkflowDetailVO result = workflowService.getWorkflowDetail(1L, 1L, false);

        String config = result.getNodes().get(0).getConfig();
        assertTrue(config.contains("\"description\":\"Public skill description\""));
        assertTrue(config.contains("\"descriptionVisible\":true"));
        assertTrue(config.contains("\"descriptionEditable\":false"));
    }

    @Test
    void getWorkflowDetailForUser_allowsAgentCreatorToEditAgentRefDescription() {
        WorkflowNode agentNode = new WorkflowNode();
        agentNode.setId(3L);
        agentNode.setWorkflowId(1L);
        agentNode.setNodeId("agent-ref-1");
        agentNode.setType("AGENT_REF");
        agentNode.setPositionX(100.0);
        agentNode.setPositionY(100.0);
        agentNode.setLabel("Agent");
        agentNode.setConfig("""
                {"agentId":4,"agentName":"Agent","nodeAlias":"agentOne"}
                """);
        Agent agent = new Agent();
        agent.setId(4L);
        agent.setCreatedBy(1L);
        agent.setDescription("Agent description");

        when(workflowMapper.selectById(1L)).thenReturn(testWorkflow);
        when(workflowNodeMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(agentNode));
        when(workflowEdgeMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        when(agentMapper.selectById(4L)).thenReturn(agent);

        WorkflowDetailVO result = workflowService.getWorkflowDetail(1L, 1L, false);

        String config = result.getNodes().get(0).getConfig();
        assertTrue(config.contains("\"description\":\"Agent description\""));
        assertTrue(config.contains("\"descriptionVisible\":true"));
        assertTrue(config.contains("\"descriptionEditable\":true"));
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
    void createWorkflow_withRequestNodes_doesNotGenerateExtraStartEndNodes() {
        WorkflowNodeDTO nodeDTO = WorkflowNodeDTO.builder()
                .nodeId("start-1")
                .type("START")
                .positionX(100.0)
                .positionY(100.0)
                .label("Start")
                .build();

        WorkflowCreateRequest request = WorkflowCreateRequest.builder()
                .name("Custom workflow")
                .nodes(List.of(nodeDTO))
                .build();

        when(workflowMapper.insert(any(Workflow.class))).thenAnswer(invocation -> {
            Workflow w = invocation.getArgument(0);
            w.setId(1L);
            return 1;
        });
        when(workflowNodeMapper.insert(any(WorkflowNode.class))).thenReturn(1);

        workflowService.createWorkflow(request, 1L);

        ArgumentCaptor<WorkflowNode> nodeCaptor = ArgumentCaptor.forClass(WorkflowNode.class);
        verify(workflowNodeMapper, times(1)).insert(nodeCaptor.capture());
        assertEquals("start-1", nodeCaptor.getValue().getNodeId());
        assertEquals("START", nodeCaptor.getValue().getType());
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
        when(workflowEdgeMapper.deletePhysicallyByWorkflowId(1L)).thenReturn(1);
        when(workflowNodeMapper.deletePhysicallyByWorkflowId(1L)).thenReturn(2);
        when(workflowNodeMapper.insert(any(WorkflowNode.class))).thenReturn(1);
        when(workflowEdgeMapper.insert(any(WorkflowEdge.class))).thenReturn(1);

        WorkflowVO result = workflowService.updateWorkflow(1L, request, 1L);

        assertEquals("更新后的工作流", result.getName());
        verify(workflowEdgeMapper).deletePhysicallyByWorkflowId(1L);
        verify(workflowNodeMapper).deletePhysicallyByWorkflowId(1L);
        verify(workflowNodeMapper, never()).delete(any(LambdaQueryWrapper.class));
        verify(workflowEdgeMapper, never()).delete(any(LambdaQueryWrapper.class));
        verify(workflowNodeMapper).insert(any(WorkflowNode.class));
        verify(workflowEdgeMapper).insert(any(WorkflowEdge.class));
    }

    @Test
    void updateWorkflow_notOwner_throwsException() {
        testWorkflow.setOwnerId(1L);
        when(workflowMapper.selectById(1L)).thenReturn(testWorkflow);

        WorkflowCreateRequest request = WorkflowCreateRequest.builder()
                .name("Other user update")
                .description("not allowed")
                .nodes(List.of())
                .edges(List.of())
                .build();

        assertThrows(BusinessException.class,
                () -> workflowService.updateWorkflow(1L, request, 999L));

        verify(workflowMapper, never()).updateById(any(Workflow.class));
        verify(workflowNodeMapper, never()).deletePhysicallyByWorkflowId(anyLong());
        verify(workflowEdgeMapper, never()).deletePhysicallyByWorkflowId(anyLong());
    }

    @Test
    void updateWorkflow_agentOwnerSyncsSkillPromptConfigToFirstStep() {
        WorkflowNodeDTO nodeDTO = WorkflowNodeDTO.builder()
                .nodeId("skill-1")
                .type("SKILL")
                .positionX(200.0)
                .positionY(200.0)
                .label("Skill")
                .config("""
                        {"skillId":10,"agentId":4,"nodeAlias":"skillOne","systemPrompt":"new system","promptTemplate":"new {{input}}","model":"doubao","temperature":0.3,"outputKey":"summary"}
                        """)
                .build();
        WorkflowCreateRequest request = WorkflowCreateRequest.builder()
                .name("Update")
                .nodes(List.of(nodeDTO))
                .edges(List.of())
                .build();
        Agent agent = new Agent();
        agent.setId(4L);
        agent.setCreatedBy(1L);
        Skill skill = new Skill();
        skill.setId(10L);
        skill.setAgentId(4L);
        SkillStep step = new SkillStep();
        step.setId(100L);
        step.setSkillId(10L);
        step.setStepOrder(1);
        step.setAction("LLM_CALL");
        step.setConfig("{\"promptTemplate\":\"old {{input}}\",\"outputKey\":\"old\"}");

        when(workflowMapper.selectById(1L)).thenReturn(testWorkflow);
        when(agentMapper.selectById(4L)).thenReturn(agent);
        when(skillMapper.selectById(10L)).thenReturn(skill);
        when(skillStepMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(step));

        workflowService.updateWorkflow(1L, request, 1L, false);

        ArgumentCaptor<SkillStep> stepCaptor = ArgumentCaptor.forClass(SkillStep.class);
        verify(skillStepMapper).updateById(stepCaptor.capture());
        assertTrue(stepCaptor.getValue().getConfig().contains("new system"));
        assertTrue(stepCaptor.getValue().getConfig().contains("new {{input}}"));
        assertTrue(stepCaptor.getValue().getConfig().contains("\"outputKey\":\"summary\""));
    }

    @Test
    void updateWorkflow_nonAgentOwnerCannotModifySkillPromptFields() {
        WorkflowNodeDTO nodeDTO = WorkflowNodeDTO.builder()
                .nodeId("skill-1")
                .type("SKILL")
                .positionX(200.0)
                .positionY(200.0)
                .label("Skill")
                .config("""
                        {"skillId":10,"agentId":4,"nodeAlias":"skillOne","systemPrompt":"new system","promptTemplate":"new {{input}}"}
                        """)
                .build();
        WorkflowCreateRequest request = WorkflowCreateRequest.builder()
                .name("Update")
                .nodes(List.of(nodeDTO))
                .edges(List.of())
                .build();
        Agent agent = new Agent();
        agent.setId(4L);
        agent.setCreatedBy(99L);
        Skill skill = new Skill();
        skill.setId(10L);
        skill.setAgentId(4L);

        when(workflowMapper.selectById(1L)).thenReturn(testWorkflow);
        when(agentMapper.selectById(4L)).thenReturn(agent);
        when(skillMapper.selectById(10L)).thenReturn(skill);

        assertThrows(BusinessException.class,
                () -> workflowService.updateWorkflow(1L, request, 1L, false));

        verify(skillStepMapper, never()).updateById(any(SkillStep.class));
        verify(workflowNodeMapper, never()).insert(any(WorkflowNode.class));
    }

    @Test
    void updateWorkflow_nonCreatorCannotOverwriteSkillNodeDescription() {
        WorkflowNode existingNode = new WorkflowNode();
        existingNode.setNodeId("skill-1");
        existingNode.setType("SKILL");
        existingNode.setConfig("""
                {"skillId":10,"agentId":4,"nodeAlias":"skillOne","description":"Creator note"}
                """);
        WorkflowNodeDTO nodeDTO = WorkflowNodeDTO.builder()
                .nodeId("skill-1")
                .type("SKILL")
                .positionX(200.0)
                .positionY(200.0)
                .label("Skill")
                .config("""
                        {"skillId":10,"agentId":4,"nodeAlias":"skillOne","description":"Tampered note"}
                        """)
                .build();
        WorkflowCreateRequest request = WorkflowCreateRequest.builder()
                .name("Update")
                .nodes(List.of(nodeDTO))
                .edges(List.of())
                .build();
        Agent agent = new Agent();
        agent.setId(4L);
        agent.setCreatedBy(99L);
        Skill skill = new Skill();
        skill.setId(10L);
        skill.setAgentId(4L);
        skill.setCreatedBy(99L);
        skill.setDescription("Source skill description");

        when(workflowMapper.selectById(1L)).thenReturn(testWorkflow);
        when(workflowNodeMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(existingNode));
        when(agentMapper.selectById(4L)).thenReturn(agent);
        when(skillMapper.selectById(10L)).thenReturn(skill);

        workflowService.updateWorkflow(1L, request, 1L, false);

        ArgumentCaptor<WorkflowNode> nodeCaptor = ArgumentCaptor.forClass(WorkflowNode.class);
        verify(workflowNodeMapper).insert(nodeCaptor.capture());
        assertTrue(nodeCaptor.getValue().getConfig().contains("\"description\":\"Creator note\""));
        assertFalse(nodeCaptor.getValue().getConfig().contains("Tampered note"));
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

        WorkflowVO result = workflowService.duplicateWorkflow(1L, 1L);

        assertNotNull(result);
        verify(workflowMapper).insert(any(Workflow.class));
    }
}
