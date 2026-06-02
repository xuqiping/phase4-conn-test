// agent-platform/backend/src/test/java/com/superprogrammer/agent/controller/AgentControllerTest.java
package com.superprogrammer.agent.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.agent.dto.*;
import com.superprogrammer.agent.service.AgentService;
import com.superprogrammer.agent.service.MarkdownSyncService;
import com.superprogrammer.auth.security.JwtUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.Arrays;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(com.superprogrammer.common.config.TestSecurityConfig.class)
class AgentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AgentService agentService;

    @MockBean
    private MarkdownSyncService markdownSyncService;

    @MockBean(name = "jwtUtil")
    private JwtUtil jwtUtil;

    @Test
    void listAgents_returnsAgentList() throws Exception {
        AgentVO agentVO = AgentVO.builder()
                .id(1L)
                .name("代码助手")
                .description("帮助编写和调试代码")
                .status("PUBLISHED")
                .groupId(1L)
                .groupName("通用助手")
                .skillCount(3)
                .createdAt(OffsetDateTime.now())
                .build();
        when(agentService.listAgents(isNull(), isNull(), isNull()))
                .thenReturn(Arrays.asList(agentVO));

        mockMvc.perform(get("/api/agents")
                        .header("Authorization", "Bearer test-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].name").value("代码助手"))
                .andExpect(jsonPath("$.data[0].groupName").value("通用助手"));
    }

    @Test
    void listAgents_withGroupId_filtersCorrectly() throws Exception {
        when(agentService.listAgents(eq(1L), isNull(), isNull()))
                .thenReturn(Arrays.asList());

        mockMvc.perform(get("/api/agents?groupId=1")
                        .header("Authorization", "Bearer test-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void listAgents_withKeyword_filtersCorrectly() throws Exception {
        when(agentService.listAgents(isNull(), eq("代码"), isNull()))
                .thenReturn(Arrays.asList());

        mockMvc.perform(get("/api/agents?keyword=代码")
                        .header("Authorization", "Bearer test-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void getAgentDetail_returnsDetail() throws Exception {
        AgentDetailVO detailVO = AgentDetailVO.builder()
                .id(1L)
                .name("代码助手")
                .description("帮助编写和调试代码")
                .status("PUBLISHED")
                .groupId(1L)
                .groupName("通用助手")
                .skills(Arrays.asList(
                        SkillVO.builder().id(1L).name("代码生成").type("SEQUENCE").build()
                ))
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .build();
        when(agentService.getAgentDetail(1L)).thenReturn(detailVO);

        mockMvc.perform(get("/api/agents/1")
                        .header("Authorization", "Bearer test-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("代码助手"))
                .andExpect(jsonPath("$.data.skills[0].name").value("代码生成"));
    }

    @Test
    void getSkillDetail_returnsDetailWithSteps() throws Exception {
        SkillDetailVO detailVO = SkillDetailVO.builder()
                .id(1L)
                .agentId(1L)
                .agentName("代码助手")
                .name("代码生成")
                .type("SEQUENCE")
                .steps(Arrays.asList(
                        SkillDetailVO.SkillStepVO.builder()
                                .stepOrder(1)
                                .name("理解需求")
                                .action("LLM_CALL")
                                .build()
                ))
                .build();
        when(agentService.getSkillDetail(1L)).thenReturn(detailVO);

        mockMvc.perform(get("/api/skills/1")
                        .header("Authorization", "Bearer test-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("代码生成"))
                .andExpect(jsonPath("$.data.steps[0].action").value("LLM_CALL"));
    }

    @Test
    void syncFromMarkdown_returnsSyncCount() throws Exception {
        when(markdownSyncService.syncAll(anyLong())).thenReturn(10);

        mockMvc.perform(post("/api/agents/sync")
                        .header("Authorization", "Bearer test-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(10));
    }
}
