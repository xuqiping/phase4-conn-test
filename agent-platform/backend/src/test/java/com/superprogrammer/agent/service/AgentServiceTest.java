package com.superprogrammer.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.superprogrammer.agent.dto.AgentCopyRequest;
import com.superprogrammer.agent.dto.AgentDetailVO;
import com.superprogrammer.agent.dto.AgentGroupVO;
import com.superprogrammer.agent.dto.AgentVO;
import com.superprogrammer.agent.dto.SkillDetailVO;
import com.superprogrammer.agent.dto.SkillVO;
import com.superprogrammer.agent.entity.Agent;
import com.superprogrammer.agent.entity.AgentGroup;
import com.superprogrammer.agent.entity.Skill;
import com.superprogrammer.agent.entity.SkillStep;
import com.superprogrammer.agent.mapper.AgentGroupMapper;
import com.superprogrammer.agent.mapper.AgentMapper;
import com.superprogrammer.agent.mapper.SkillMapper;
import com.superprogrammer.agent.mapper.SkillStepMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AgentServiceTest {

    @Mock private AgentGroupMapper agentGroupMapper;
    @Mock private AgentMapper agentMapper;
    @Mock private SkillService skillService;
    @Mock private SkillMapper skillMapper;
    @Mock private SkillStepMapper skillStepMapper;
    @Mock private AgentPermissionService agentPermissionService;

    @InjectMocks
    private AgentService agentService;

    private AgentGroup testGroup;
    private Agent testAgent;

    @BeforeEach
    void setUp() {
        testGroup = new AgentGroup();
        testGroup.setId(1L);
        testGroup.setName("General");
        testGroup.setIcon("robot");
        testGroup.setDescription("General agents");
        testGroup.setSortOrder(1);
        testGroup.setCreatedAt(OffsetDateTime.now());

        testAgent = new Agent();
        testAgent.setId(1L);
        testAgent.setName("CodeBot");
        testAgent.setDescription("Helps code");
        testAgent.setGroupId(1L);
        testAgent.setStatus("PUBLISHED");
        testAgent.setCreatedBy(1L);
        testAgent.setCreatedAt(OffsetDateTime.now());
        testAgent.setUpdatedAt(OffsetDateTime.now());
    }

    @Test
    void listGroups_returnsAllGroups() {
        when(agentGroupMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(testGroup));
        when(agentMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(5L);

        List<AgentGroupVO> result = agentService.listGroups();

        assertEquals(1, result.size());
        assertEquals("General", result.get(0).getName());
        assertEquals(5L, result.get(0).getAgentCount());
    }

    @Test
    void listAgents_byGroupId_filtersCorrectly() {
        when(agentMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(testAgent));
        when(agentGroupMapper.selectById(1L)).thenReturn(testGroup);
        when(skillMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(3L);

        List<AgentVO> result = agentService.listAgents(1L, null, null);

        assertEquals(1, result.size());
        assertEquals("CodeBot", result.get(0).getName());
        assertEquals("General", result.get(0).getGroupName());
        assertEquals(3, result.get(0).getSkillCount());
    }

    @Test
    void listAgentsForUser_returnsOnlyUsableAgents() {
        Agent hiddenAgent = new Agent();
        hiddenAgent.setId(2L);
        hiddenAgent.setName("Hidden");
        hiddenAgent.setGroupId(1L);
        hiddenAgent.setStatus("PUBLISHED");
        hiddenAgent.setCreatedAt(OffsetDateTime.now());
        hiddenAgent.setUpdatedAt(OffsetDateTime.now());

        when(agentMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(testAgent, hiddenAgent));
        when(agentGroupMapper.selectById(1L)).thenReturn(testGroup);
        when(skillMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);
        when(agentPermissionService.canUse(1L, 100L, false)).thenReturn(true);
        when(agentPermissionService.canUse(2L, 100L, false)).thenReturn(false);

        List<AgentVO> result = agentService.listAgents(null, null, null, 100L, false);

        assertEquals(1, result.size());
        assertEquals(1L, result.get(0).getId());
    }

    @Test
    void getAgentDetail_success() {
        when(agentMapper.selectById(1L)).thenReturn(testAgent);
        when(agentGroupMapper.selectById(1L)).thenReturn(testGroup);
        when(skillService.listByAgentId(1L)).thenReturn(List.of(SkillVO.builder()
                .id(1L)
                .name("Generate Code")
                .description("Generate code")
                .type("SEQUENCE")
                .sortOrder(1)
                .build()));

        AgentDetailVO result = agentService.getAgentDetail(1L);

        assertEquals("CodeBot", result.getName());
        assertEquals("General", result.getGroupName());
        assertEquals("PUBLISHED", result.getStatus());
        assertNotNull(result.getSkills());
        assertEquals(1, result.getSkills().size());
    }

    @Test
    void getAgentDetail_notFound_throwsException() {
        when(agentMapper.selectById(999L)).thenReturn(null);

        assertThrows(BusinessException.class, () -> agentService.getAgentDetail(999L));
    }

    @Test
    void getAgentDetailForUser_rejectsWithoutUsePermission() {
        when(agentPermissionService.canUse(1L, 100L, false)).thenReturn(false);

        assertThrows(BusinessException.class, () -> agentService.getAgentDetail(1L, 100L, false));
    }

    @Test
    void getAgentDetailForUser_redactsSensitiveConfigWithoutReadPromptPermission() {
        testAgent.setConfig("{\"systemPrompt\":\"secret\"}");
        SkillVO skill = SkillVO.builder()
                .id(9L)
                .name("Analyze")
                .description("desc")
                .type("SEQUENCE")
                .build();
        when(agentPermissionService.canUse(1L, 100L, false)).thenReturn(true);
        when(agentPermissionService.canReadPrompt(1L, 100L, false)).thenReturn(false);
        when(agentMapper.selectById(1L)).thenReturn(testAgent);
        when(agentGroupMapper.selectById(1L)).thenReturn(testGroup);
        when(skillService.listByAgentId(1L)).thenReturn(List.of(skill));

        AgentDetailVO result = agentService.getAgentDetail(1L, 100L, false);

        assertNull(result.getConfig());
        assertEquals(1, result.getSkills().size());
    }

    @Test
    void getSkillDetailForUser_redactsStepConfigWithoutReadPromptPermission() {
        SkillDetailVO detail = SkillDetailVO.builder()
                .id(9L)
                .agentId(1L)
                .agentName("CodeBot")
                .name("Analyze")
                .config("{\"prompt\":\"secret\"}")
                .steps(List.of(SkillDetailVO.SkillStepVO.builder()
                        .id(91L)
                        .stepOrder(1)
                        .name("Call")
                        .action("LLM_CALL")
                        .config("{\"promptTemplate\":\"secret\"}")
                        .build()))
                .build();
        when(skillService.getDetail(9L)).thenReturn(detail);
        when(agentPermissionService.canUse(1L, 100L, false)).thenReturn(true);
        when(agentPermissionService.canReadPrompt(1L, 100L, false)).thenReturn(false);

        SkillDetailVO result = agentService.getSkillDetail(9L, 100L, false);

        assertNull(result.getConfig());
        assertNull(result.getSteps().get(0).getConfig());
    }

    @Test
    void getSkillDetail_success() {
        when(skillService.getDetail(1L)).thenReturn(SkillDetailVO.builder()
                .id(1L)
                .agentId(1L)
                .agentName("CodeBot")
                .name("Generate Code")
                .description("Generate code")
                .type("SEQUENCE")
                .sortOrder(1)
                .steps(Collections.emptyList())
                .build());

        SkillDetailVO result = agentService.getSkillDetail(1L);

        assertEquals("Generate Code", result.getName());
        assertEquals("CodeBot", result.getAgentName());
        assertNotNull(result.getSteps());
    }

    @Test
    void getSkillDetail_notFound_throwsException() {
        when(skillService.getDetail(999L)).thenThrow(new BusinessException(ErrorCode.NOT_FOUND, "Skill not found"));

        assertThrows(BusinessException.class, () -> agentService.getSkillDetail(999L));
    }

    @Test
    void copyAgent_createsNewAgentForUserWithCopyPermission() {
        testAgent.setConfig("{\"systemPrompt\":\"source\"}");
        when(agentPermissionService.canCopy(1L, 100L, false)).thenReturn(true);
        when(agentMapper.selectById(1L)).thenReturn(testAgent);
        when(agentMapper.insert(any(Agent.class))).thenAnswer(invocation -> {
            Agent agent = invocation.getArgument(0);
            agent.setId(20L);
            return 1;
        });

        Skill sourceSkill = new Skill();
        sourceSkill.setId(10L);
        sourceSkill.setAgentId(1L);
        sourceSkill.setName("Analyze");
        sourceSkill.setDescription("Analyze input");
        sourceSkill.setType("SEQUENCE");
        sourceSkill.setConfig("{\"inputParams\":[]}");
        sourceSkill.setSortOrder(1);
        when(skillMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(sourceSkill));
        when(skillMapper.insert(any(Skill.class))).thenAnswer(invocation -> {
            Skill skill = invocation.getArgument(0);
            skill.setId(30L);
            return 1;
        });

        SkillStep sourceStep = new SkillStep();
        sourceStep.setSkillId(10L);
        sourceStep.setStepOrder(1);
        sourceStep.setName("Call LLM");
        sourceStep.setAction("LLM_CALL");
        sourceStep.setConfig("{\"promptTemplate\":\"hello\"}");
        when(skillStepMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(sourceStep));

        AgentCopyRequest request = new AgentCopyRequest();
        request.setName("My Copy");
        request.setDescription("Copied");

        AgentDetailVO result = agentService.copyAgent(1L, request, 100L, false);

        assertEquals(20L, result.getId());
        assertEquals("My Copy", result.getName());
        assertEquals("Copied", result.getDescription());
        verify(agentMapper).insert(argThat(agent -> agent.getCreatedBy().equals(100L)));
        verify(skillMapper).insert(argThat(skill -> skill.getAgentId().equals(20L) && skill.getCreatedBy().equals(100L)));
        verify(skillStepMapper).insert(argThat(step -> step.getSkillId().equals(30L) && step.getCreatedBy().equals(100L)));
    }
}
