// agent-platform/backend/src/test/java/com/superprogrammer/agent/service/MarkdownSyncServiceTest.java
package com.superprogrammer.agent.service;

import com.superprogrammer.agent.entity.Agent;
import com.superprogrammer.agent.entity.AgentGroup;
import com.superprogrammer.agent.entity.Skill;
import com.superprogrammer.agent.entity.SkillStep;
import com.superprogrammer.agent.mapper.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MarkdownSyncServiceTest {

    @Mock
    private AgentGroupMapper agentGroupMapper;

    @Mock
    private AgentMapper agentMapper;

    @Mock
    private SkillMapper skillMapper;

    @Mock
    private SkillStepMapper skillStepMapper;

    @InjectMocks
    private MarkdownSyncService markdownSyncService;

    private String topLevelRouterContent;
    private String subAgentRouterContent;

    @BeforeEach
    void setUp() {
        topLevelRouterContent = "# Agent 路由\n" +
                "\n" +
                "## 通用助手\n" +
                "\n" +
                "| Agent | 描述 |\n" +
                "|-------|------|\n" +
                "| 代码助手 | 帮助编写和调试代码 |\n" +
                "| 翻译助手 | 多语言翻译 |\n" +
                "\n" +
                "## 数据分析\n" +
                "\n" +
                "| Agent | 描述 |\n" +
                "|-------|------|\n" +
                "| SQL助手 | SQL查询生成与优化 |";

        subAgentRouterContent = "# 代码助手 技能路由\n" +
                "\n" +
                "## 代码生成\n" +
                "\n" +
                "**类型:** SEQUENCE\n" +
                "\n" +
                "**描述:** 根据自然语言需求生成代码\n" +
                "\n" +
                "### 步骤\n" +
                "\n" +
                "| 序号 | 名称 | 动作 |\n" +
                "|------|------|------|\n" +
                "| 1 | 理解需求 | LLM_CALL |\n" +
                "| 2 | 生成代码 | LLM_CALL |\n" +
                "| 3 | 代码审查 | LLM_CALL |\n" +
                "\n" +
                "## 代码调试\n" +
                "\n" +
                "**类型:** SEQUENCE\n" +
                "\n" +
                "**描述:** 分析和修复代码错误";
    }

    @Test
    void parseTopLevelRouter_extractsGroupsAndAgents() {
        // 模拟分组不存在（首次同步）
        when(agentGroupMapper.selectOne(any())).thenReturn(null);
        when(agentGroupMapper.insert(any())).thenReturn(1);
        when(agentMapper.selectOne(any())).thenReturn(null);
        when(agentMapper.insert(any())).thenReturn(1);

        int count = markdownSyncService.parseTopLevelRouter(topLevelRouterContent, 1L);

        // 应解析出2个分组：通用助手、数据分析
        // 每个分组下各有2和1个Agent
        verify(agentGroupMapper, times(3)).insert(any(AgentGroup.class));
        verify(agentMapper, times(3)).insert(any(Agent.class));
        assertTrue(count > 0);
    }

    @Test
    void parseTopLevelRouter_existingGroup_updated() {
        AgentGroup existingGroup = new AgentGroup();
        existingGroup.setId(1L);
        existingGroup.setName("通用助手");

        when(agentGroupMapper.selectOne(any())).thenReturn(existingGroup);
        when(agentGroupMapper.updateById(any())).thenReturn(1);
        when(agentMapper.selectOne(any())).thenReturn(null);
        when(agentMapper.insert(any())).thenReturn(1);

        markdownSyncService.parseTopLevelRouter(topLevelRouterContent, 1L);

        // 已存在的分组应该被更新而不是插入
        verify(agentGroupMapper, times(3)).updateById(any(AgentGroup.class));
        verify(agentGroupMapper, never()).insert(any(AgentGroup.class));
    }

    @Test
    void parseSubAgentRouter_extractsSkillsAndSteps() {
        Agent agent = new Agent();
        agent.setId(1L);
        agent.setName("代码助手");

        when(skillMapper.selectOne(any())).thenReturn(null);
        when(skillMapper.insert(any())).thenReturn(1);
        when(skillStepMapper.selectOne(any())).thenReturn(null);
        when(skillStepMapper.insert(any())).thenReturn(1);

        int count = markdownSyncService.parseSubAgentRouter(subAgentRouterContent, agent, 1L);

        // 应解析出2个技能：代码生成(含3个步骤)、代码调试(无步骤)
        ArgumentCaptor<Skill> skillCaptor = ArgumentCaptor.forClass(Skill.class);
        verify(skillMapper, times(2)).insert(skillCaptor.capture());

        List<Skill> insertedSkills = skillCaptor.getAllValues();
        assertEquals("代码生成", insertedSkills.get(0).getName());
        assertEquals("** SEQUENCE", insertedSkills.get(0).getType());
        assertEquals("代码调试", insertedSkills.get(1).getName());

        // 代码生成技能有3个步骤
        verify(skillStepMapper, times(3)).insert(any(SkillStep.class));
        assertTrue(count > 0);
    }

    @Test
    void parseTopLevelRouter_emptyContent_returnsZero() {
        int count = markdownSyncService.parseTopLevelRouter("", 1L);
        assertEquals(0, count);
    }

    @Test
    void parseTopLevelRouter_noTables_returnsZero() {
        int count = markdownSyncService.parseTopLevelRouter("# 标题\n\n没有表格内容", 1L);
        assertEquals(0, count);
    }

    @Test
    void parseSubAgentRouter_emptyContent_returnsZero() {
        Agent agent = new Agent();
        agent.setId(1L);
        int count = markdownSyncService.parseSubAgentRouter("", agent, 1L);
        assertEquals(0, count);
    }

    @Test
    void parseSubAgentRouter_existingSkill_updated() {
        Agent agent = new Agent();
        agent.setId(1L);
        agent.setName("代码助手");

        Skill existingSkill = new Skill();
        existingSkill.setId(10L);
        existingSkill.setName("代码生成");
        existingSkill.setAgentId(1L);

        when(skillMapper.selectOne(any())).thenReturn(existingSkill);
        when(skillMapper.updateById(any())).thenReturn(1);
        when(skillStepMapper.selectOne(any())).thenReturn(null);
        when(skillStepMapper.insert(any())).thenReturn(1);

        markdownSyncService.parseSubAgentRouter(subAgentRouterContent, agent, 1L);

        // 代码生成+代码调试两个技能均已存在，各更新一次
        verify(skillMapper, times(2)).updateById(any(Skill.class));
    }
}
