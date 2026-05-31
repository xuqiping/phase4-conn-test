// agent-platform/backend/src/test/java/com/superprogrammer/agent/service/AgentServiceTest.java
package com.superprogrammer.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.superprogrammer.agent.dto.*;
import com.superprogrammer.agent.entity.Agent;
import com.superprogrammer.agent.entity.AgentGroup;
import com.superprogrammer.agent.entity.Skill;
import com.superprogrammer.agent.mapper.AgentGroupMapper;
import com.superprogrammer.agent.mapper.AgentMapper;
import com.superprogrammer.agent.mapper.SkillMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentServiceTest {

    @Mock
    private AgentGroupMapper agentGroupMapper;

    @Mock
    private AgentMapper agentMapper;

    @Mock
    private SkillService skillService;

    @Mock
    private SkillMapper skillMapper;

    @InjectMocks
    private AgentService agentService;

    private AgentGroup testGroup;
    private Agent testAgent;
    private Skill testSkill;

    @BeforeEach
    void setUp() {
        testGroup = new AgentGroup();
        testGroup.setId(1L);
        testGroup.setName("通用助手");
        testGroup.setIcon("robot");
        testGroup.setDescription("通用对话和问答类Agent");
        testGroup.setSortOrder(1);
        testGroup.setCreatedAt(OffsetDateTime.now());

        testAgent = new Agent();
        testAgent.setId(1L);
        testAgent.setName("代码助手");
        testAgent.setDescription("帮助编写和调试代码");
        testAgent.setGroupId(1L);
        testAgent.setStatus("PUBLISHED");
        testAgent.setCreatedAt(OffsetDateTime.now());
        testAgent.setUpdatedAt(OffsetDateTime.now());

        testSkill = new Skill();
        testSkill.setId(1L);
        testSkill.setAgentId(1L);
        testSkill.setName("代码生成");
        testSkill.setDescription("根据需求生成代码");
        testSkill.setType("SEQUENCE");
        testSkill.setSortOrder(1);
        testSkill.setCreatedAt(OffsetDateTime.now());
    }

    @Test
    void listGroups_returnsAllGroups() {
        when(agentGroupMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Arrays.asList(testGroup));
        when(agentMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(5L);

        List<AgentGroupVO> result = agentService.listGroups();

        assertEquals(1, result.size());
        assertEquals("通用助手", result.get(0).getName());
        assertEquals(5L, result.get(0).getAgentCount());
    }

    @Test
    void listAgents_byGroupId_filtersCorrectly() {
        when(agentMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Arrays.asList(testAgent));
        when(agentGroupMapper.selectById(1L)).thenReturn(testGroup);
        when(skillMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(3L);

        List<AgentVO> result = agentService.listAgents(1L, null);

        assertEquals(1, result.size());
        assertEquals("代码助手", result.get(0).getName());
        assertEquals("通用助手", result.get(0).getGroupName());
        assertEquals(3, result.get(0).getSkillCount());
    }

    @Test
    void listAgents_byKeyword_filtersCorrectly() {
        when(agentMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Arrays.asList(testAgent));
        when(agentGroupMapper.selectById(1L)).thenReturn(testGroup);
        when(skillMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(2L);

        List<AgentVO> result = agentService.listAgents(null, "代码");

        assertEquals(1, result.size());
        assertEquals("代码助手", result.get(0).getName());
    }

    @Test
    void listAgents_noFilters_returnsAll() {
        when(agentMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Arrays.asList(testAgent));
        when(agentGroupMapper.selectById(1L)).thenReturn(testGroup);
        when(skillMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);

        List<AgentVO> result = agentService.listAgents(null, null);

        assertEquals(1, result.size());
    }

    @Test
    void getAgentDetail_success() {
        when(agentMapper.selectById(1L)).thenReturn(testAgent);
        when(agentGroupMapper.selectById(1L)).thenReturn(testGroup);
        when(skillService.listByAgentId(1L))
                .thenReturn(Arrays.asList(
                        SkillVO.builder()
                                .id(1L)
                                .name("代码生成")
                                .description("根据需求生成代码")
                                .type("SEQUENCE")
                                .sortOrder(1)
                                .build()));

        AgentDetailVO result = agentService.getAgentDetail(1L);

        assertEquals("代码助手", result.getName());
        assertEquals("通用助手", result.getGroupName());
        assertEquals("PUBLISHED", result.getStatus());
        assertNotNull(result.getSkills());
        assertEquals(1, result.getSkills().size());
        assertEquals("代码生成", result.getSkills().get(0).getName());
    }

    @Test
    void getAgentDetail_notFound_throwsException() {
        when(agentMapper.selectById(999L)).thenReturn(null);

        assertThrows(BusinessException.class, () -> agentService.getAgentDetail(999L));
    }

    @Test
    void getSkillDetail_success() {
        when(skillService.getDetail(1L))
                .thenReturn(SkillDetailVO.builder()
                        .id(1L)
                        .agentId(1L)
                        .agentName("代码助手")
                        .name("代码生成")
                        .description("根据需求生成代码")
                        .type("SEQUENCE")
                        .sortOrder(1)
                        .steps(Collections.emptyList())
                        .build());

        SkillDetailVO result = agentService.getSkillDetail(1L);

        assertEquals("代码生成", result.getName());
        assertEquals("代码助手", result.getAgentName());
        assertNotNull(result.getSteps());
    }

    @Test
    void getSkillDetail_notFound_throwsException() {
        when(skillService.getDetail(999L)).thenThrow(new BusinessException(ErrorCode.NOT_FOUND, "技能不存在"));

        assertThrows(BusinessException.class, () -> agentService.getSkillDetail(999L));
    }
}
