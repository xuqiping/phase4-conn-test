package com.superprogrammer.engine.router;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.agent.entity.Agent;
import com.superprogrammer.agent.entity.Skill;
import com.superprogrammer.agent.mapper.SkillMapper;
import com.superprogrammer.llm.LlmGateway;
import com.superprogrammer.llm.dto.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AgentRouterTest {

    @Mock
    private SkillMapper skillMapper;

    @Mock
    private LlmGateway llmGateway;

    private AgentRouter router;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        router = new AgentRouter(skillMapper, llmGateway, objectMapper);
    }

    @Test
    void route_matchingKeyword_shouldReturnMatchingSkills() {
        Agent agent = new Agent();
        agent.setId(1L);
        agent.setConfig("{\"routingRules\":[{\"keywords\":[\"代码\",\"bug\"],\"skillIds\":[1,2]}]}");

        RoutingResult result = router.route(agent, "帮我写一段Java代码");

        assertEquals(2, result.getSkillIds().size());
        assertTrue(result.getSkillIds().contains(1L));
        assertTrue(result.getSkillIds().contains(2L));
        verify(llmGateway, never()).chat(any());
    }

    @Test
    void route_noMatchingKeyword_shouldUseLlm() {
        Agent agent = new Agent();
        agent.setId(1L);
        agent.setConfig("{\"routingRules\":[{\"keywords\":[\"代码\"],\"skillIds\":[1]}]}");

        Skill s1 = new Skill();
        s1.setId(2L);
        s1.setName("文档生成");
        s1.setDescription("生成项目文档");

        when(skillMapper.selectList(any())).thenReturn(List.of(s1));

        LlmResponse llmResp = LlmResponse.builder().content("[2]").build();
        when(llmGateway.chat(any())).thenReturn(llmResp);

        RoutingResult result = router.route(agent, "帮我写个文档");

        assertFalse(result.getSkillIds().isEmpty());
        verify(llmGateway).chat(any());
    }

    @Test
    void route_noRoutingRules_shouldUseLlm() {
        Agent agent = new Agent();
        agent.setId(1L);
        agent.setConfig("{}");

        Skill s1 = new Skill();
        s1.setId(1L);
        s1.setName("测试技能");
        when(skillMapper.selectList(any())).thenReturn(List.of(s1));

        LlmResponse llmResp = LlmResponse.builder().content("[1]").build();
        when(llmGateway.chat(any())).thenReturn(llmResp);

        RoutingResult result = router.route(agent, "随便问个问题");

        assertEquals(List.of(1L), result.getSkillIds());
    }
}
