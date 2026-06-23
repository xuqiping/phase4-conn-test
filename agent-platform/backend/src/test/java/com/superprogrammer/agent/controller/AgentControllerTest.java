package com.superprogrammer.agent.controller;

import com.superprogrammer.agent.dto.AgentAccessVO;
import com.superprogrammer.agent.dto.AgentCopyRequest;
import com.superprogrammer.agent.dto.AgentDetailVO;
import com.superprogrammer.agent.dto.AgentPermissionVO;
import com.superprogrammer.agent.dto.AgentVO;
import com.superprogrammer.agent.dto.SkillDetailVO;
import com.superprogrammer.agent.dto.SkillSaveRequest;
import com.superprogrammer.agent.dto.SkillVO;
import com.superprogrammer.agent.service.AgentPermissionService;
import com.superprogrammer.agent.service.AgentService;
import com.superprogrammer.agent.service.MarkdownSyncService;
import com.superprogrammer.agent.service.SkillService;
import com.superprogrammer.auth.security.JwtUtil;
import com.superprogrammer.auth.security.RequirePermission;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Tag("integration")
@ActiveProfiles("it")
@Import(com.superprogrammer.common.config.TestSecurityConfig.class)
class AgentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AgentService agentService;

    @MockBean
    private MarkdownSyncService markdownSyncService;

    @MockBean
    private SkillService skillService;

    @MockBean
    private AgentPermissionService agentPermissionService;

    @MockBean(name = "jwtUtil")
    private JwtUtil jwtUtil;

    @Test
    void listAgents_returnsAgentList() throws Exception {
        AgentVO agentVO = AgentVO.builder()
                .id(1L)
                .name("CodeBot")
                .description("Helps write code")
                .status("PUBLISHED")
                .groupId(1L)
                .groupName("General")
                .skillCount(3)
                .createdAt(OffsetDateTime.now())
                .build();
        when(agentService.listAgents(isNull(), isNull(), isNull(), eq(1L), eq(true)))
                .thenReturn(List.of(agentVO));

        mockMvc.perform(get("/api/agents")
                        .header("Authorization", "Bearer test-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].name").value("CodeBot"))
                .andExpect(jsonPath("$.data[0].groupName").value("General"));
    }

    @Test
    void listAgents_withGroupId_filtersCorrectly() throws Exception {
        when(agentService.listAgents(eq(1L), isNull(), isNull(), eq(1L), eq(true)))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/agents?groupId=1")
                        .header("Authorization", "Bearer test-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void listAgents_withKeyword_filtersCorrectly() throws Exception {
        when(agentService.listAgents(isNull(), eq("code"), isNull(), eq(1L), eq(true)))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/agents?keyword=code")
                        .header("Authorization", "Bearer test-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void getAgentDetail_returnsDetail() throws Exception {
        AgentDetailVO detailVO = AgentDetailVO.builder()
                .id(1L)
                .name("CodeBot")
                .description("Helps write code")
                .status("PUBLISHED")
                .groupId(1L)
                .groupName("General")
                .skills(List.of(SkillVO.builder().id(1L).name("Generate Code").type("SEQUENCE").build()))
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .build();
        when(agentService.getAgentDetail(1L, 1L, true)).thenReturn(detailVO);

        mockMvc.perform(get("/api/agents/1")
                        .header("Authorization", "Bearer test-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("CodeBot"))
                .andExpect(jsonPath("$.data.skills[0].name").value("Generate Code"));
    }

    @Test
    void getSkillDetail_returnsDetailWithSteps() throws Exception {
        SkillDetailVO detailVO = SkillDetailVO.builder()
                .id(1L)
                .agentId(1L)
                .agentName("CodeBot")
                .name("Generate Code")
                .type("SEQUENCE")
                .steps(List.of(SkillDetailVO.SkillStepVO.builder()
                        .stepOrder(1)
                        .name("Understand")
                        .action("LLM_CALL")
                        .build()))
                .build();
        when(agentService.getSkillDetail(1L, 1L, true)).thenReturn(detailVO);

        mockMvc.perform(get("/api/skills/1")
                        .header("Authorization", "Bearer test-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Generate Code"))
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

    @Test
    void createSkill_createsSkillWithSteps() throws Exception {
        SkillDetailVO detailVO = SkillDetailVO.builder()
                .id(10L)
                .agentId(1L)
                .name("Analyze")
                .description("Analyze input")
                .type("SEQUENCE")
                .sortOrder(1)
                .steps(List.of(SkillDetailVO.SkillStepVO.builder()
                        .id(100L)
                        .stepOrder(1)
                        .name("Read Input")
                        .action("parse_input")
                        .config("{}")
                        .build()))
                .build();
        when(skillService.createSkill(eq(1L), any(SkillSaveRequest.class), eq(1L))).thenReturn(detailVO);

        String body = """
                {
                  "name": "Analyze",
                  "description": "Analyze input",
                  "type": "SEQUENCE",
                  "config": "{}",
                  "sortOrder": 1,
                  "steps": [
                    { "stepOrder": 1, "name": "Read Input", "action": "parse_input", "config": "{}" }
                  ]
                }
                """;

        mockMvc.perform(post("/api/agents/1/skills")
                        .header("Authorization", "Bearer test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(10))
                .andExpect(jsonPath("$.data.name").value("Analyze"))
                .andExpect(jsonPath("$.data.steps[0].name").value("Read Input"));
    }

    @Test
    void updateSkill_updatesSkillWithSteps() throws Exception {
        SkillDetailVO detailVO = SkillDetailVO.builder()
                .id(10L)
                .agentId(1L)
                .name("Analyze v2")
                .type("SEQUENCE")
                .sortOrder(2)
                .steps(List.of())
                .build();
        when(skillService.updateSkill(eq(10L), any(SkillSaveRequest.class), eq(1L))).thenReturn(detailVO);

        mockMvc.perform(put("/api/skills/10")
                        .header("Authorization", "Bearer test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Analyze v2\",\"type\":\"SEQUENCE\",\"sortOrder\":2,\"steps\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Analyze v2"));
    }

    @Test
    void deleteSkill_deletesSkillAndSteps() throws Exception {
        mockMvc.perform(delete("/api/skills/10")
                        .header("Authorization", "Bearer test-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(skillService).deleteSkill(10L);
    }

    @Test
    void getAgentAccess_returnsCurrentUserAccess() throws Exception {
        when(agentPermissionService.resolveAccess(1L, 1L, true)).thenReturn(AgentAccessVO.builder()
                .agentId(1L)
                .canManage(true)
                .canUse(true)
                .canReadPrompt(true)
                .canCopy(true)
                .build());

        mockMvc.perform(get("/api/agents/1/access")
                        .header("Authorization", "Bearer test-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.agentId").value(1))
                .andExpect(jsonPath("$.data.canManage").value(true))
                .andExpect(jsonPath("$.data.canUse").value(true));
    }

    @Test
    void listAgentPermissions_returnsPermissionList() throws Exception {
        when(agentPermissionService.listPermissions(1L, 1L, true)).thenReturn(List.of(
                AgentPermissionVO.builder()
                        .agentId(1L)
                        .userId(2L)
                        .username("alice")
                        .canUse(true)
                        .canReadPrompt(false)
                        .canCopy(true)
                        .build()
        ));

        mockMvc.perform(get("/api/agents/1/permissions")
                        .header("Authorization", "Bearer test-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].username").value("alice"))
                .andExpect(jsonPath("$.data[0].canCopy").value(true));
    }

    @Test
    void saveAgentPermissions_delegatesToPermissionService() throws Exception {
        String body = """
                [
                  { "userId": 2, "canUse": false, "canReadPrompt": true, "canCopy": false }
                ]
                """;

        mockMvc.perform(put("/api/agents/1/permissions")
                        .header("Authorization", "Bearer test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(agentPermissionService).savePermissions(eq(1L), anyList(), eq(1L), eq(true));
    }

    @Test
    void copyAgent_createsAgentCopyForCurrentUser() throws Exception {
        when(agentService.copyAgent(eq(1L), any(AgentCopyRequest.class), eq(1L), eq(true)))
                .thenReturn(AgentDetailVO.builder()
                        .id(20L)
                        .name("My Copy")
                        .description("Copied")
                        .status("DRAFT")
                        .build());

        mockMvc.perform(post("/api/agents/1/copy")
                        .header("Authorization", "Bearer test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"My Copy\",\"description\":\"Copied\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(20))
                .andExpect(jsonPath("$.data.name").value("My Copy"));
    }

    @Test
    void skillWriteEndpoints_requireSkillManagePermission() throws Exception {
        assertPermission("createSkill", Long.class, SkillSaveRequest.class);
        assertPermission("updateSkill", Long.class, SkillSaveRequest.class);
        assertPermission("deleteSkill", Long.class);
    }

    @Test
    void objectAccessEndpoints_doNotRequireGlobalAgentReadPermission() throws Exception {
        assertNoPermission("listAgents", Long.class, String.class);
        assertNoPermission("getAgentDetail", Long.class);
        assertNoPermission("getAgentAccess", Long.class);
        assertNoPermission("listAgentSkills", Long.class);
        assertNoPermission("getSkillDetail", Long.class);
        assertNoPermission("listAgentPermissions", Long.class);
        assertNoPermission("saveAgentPermissions", Long.class, List.class);
        assertNoPermission("copyAgent", Long.class, AgentCopyRequest.class);
    }

    private void assertPermission(String methodName, Class<?>... parameterTypes) throws NoSuchMethodException {
        RequirePermission permission = AgentController.class
                .getDeclaredMethod(methodName, parameterTypes)
                .getAnnotation(RequirePermission.class);
        org.assertj.core.api.Assertions.assertThat(permission).isNotNull();
        org.assertj.core.api.Assertions.assertThat(permission.value()).isEqualTo("skill:manage");
    }

    private void assertNoPermission(String methodName, Class<?>... parameterTypes) throws NoSuchMethodException {
        RequirePermission permission = AgentController.class
                .getDeclaredMethod(methodName, parameterTypes)
                .getAnnotation(RequirePermission.class);
        org.assertj.core.api.Assertions.assertThat(permission).isNull();
    }
}
