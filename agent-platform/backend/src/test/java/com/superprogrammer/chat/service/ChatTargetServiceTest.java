package com.superprogrammer.chat.service;

import com.superprogrammer.agent.dto.AgentVO;
import com.superprogrammer.agent.entity.Agent;
import com.superprogrammer.agent.mapper.AgentMapper;
import com.superprogrammer.agent.service.AgentPermissionService;
import com.superprogrammer.agent.service.AgentService;
import com.superprogrammer.auth.security.PermissionEvaluator;
import com.superprogrammer.chat.dto.ChatTargetVO;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.workflow.dto.WorkflowVO;
import com.superprogrammer.workflow.entity.Workflow;
import com.superprogrammer.workflow.mapper.WorkflowMapper;
import com.superprogrammer.workflow.service.WorkflowService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChatTargetServiceTest {

    @Mock private AgentService agentService;
    @Mock private WorkflowService workflowService;
    @Mock private AgentMapper agentMapper;
    @Mock private WorkflowMapper workflowMapper;
    @Mock private PermissionEvaluator permissionEvaluator;
    @Mock private AgentPermissionService agentPermissionService;

    @InjectMocks
    private ChatTargetService chatTargetService;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void listTargets_returnsNonePublishedAgentsAndUserWorkflows() {
        var authentication = new UsernamePasswordAuthenticationToken(100L, "u", List.of(
                new SimpleGrantedAuthority("agent:read"),
                new SimpleGrantedAuthority("workflow:read")));
        when(permissionEvaluator.hasPermission(authentication, "workflow:read")).thenReturn(true);
        when(agentService.listAgents(null, null, null, 100L, false)).thenReturn(List.of(
                AgentVO.builder().id(10L).name("CodeBot").status("PUBLISHED").build(),
                AgentVO.builder().id(11L).name("DraftBot").status("DRAFT").build()));
        when(workflowService.listWorkflows(100L)).thenReturn(List.of(
                WorkflowVO.builder().id(5L).name("Daily Flow").ownerId(100L).status("DRAFT").build()));

        List<ChatTargetVO> targets = chatTargetService.listTargets(100L, authentication);

        assertEquals(List.of("none", "agent:10", "workflow:5"),
                targets.stream().map(ChatTargetVO::getTargetKey).toList());
    }

    @Test
    void listTargets_includesObjectAuthorizedAgentsWithoutGlobalAgentReadPermission() {
        var authentication = new UsernamePasswordAuthenticationToken(100L, "u", List.of());
        when(agentService.listAgents(null, null, null, 100L, false)).thenReturn(List.of(
                AgentVO.builder().id(10L).name("SharedBot").status("PUBLISHED").build()));

        List<ChatTargetVO> targets = chatTargetService.listTargets(100L, authentication);

        assertTrue(targets.stream().anyMatch(target -> "agent:10".equals(target.getTargetKey())));
        verify(permissionEvaluator, never()).hasPermission(authentication, "agent:read");
    }

    @Test
    void validateTarget_rejectsBothAgentAndWorkflow() {
        assertThrows(BusinessException.class, () -> chatTargetService.validateTarget(100L, 10L, 5L));
        verifyNoInteractions(agentMapper, workflowMapper);
    }

    @Test
    void validateTarget_acceptsPublishedAgentWithPermission() {
        var authentication = new UsernamePasswordAuthenticationToken(100L, "u", List.of(
                new SimpleGrantedAuthority("agent:read")));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        when(agentPermissionService.canUse(10L, 100L, false)).thenReturn(true);
        Agent agent = new Agent();
        agent.setId(10L);
        agent.setStatus("PUBLISHED");
        when(agentMapper.selectById(10L)).thenReturn(agent);

        assertDoesNotThrow(() -> chatTargetService.validateTarget(100L, 10L, null));
    }

    @Test
    void validateTarget_rejectsAgentWithoutObjectUsePermission() {
        var authentication = new UsernamePasswordAuthenticationToken(100L, "u", List.of(
                new SimpleGrantedAuthority("agent:read")));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        when(agentPermissionService.canUse(10L, 100L, false)).thenReturn(false);

        assertThrows(BusinessException.class, () -> chatTargetService.validateTarget(100L, 10L, null));
        verify(agentMapper, never()).selectById(10L);
    }

    @Test
    void validateTarget_rejectsWorkflowOwnedByAnotherUser() {
        var authentication = new UsernamePasswordAuthenticationToken(100L, "u", List.of(
                new SimpleGrantedAuthority("workflow:read")));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        when(permissionEvaluator.hasPermission(authentication, "workflow:read")).thenReturn(true);
        Workflow workflow = new Workflow();
        workflow.setId(5L);
        workflow.setOwnerId(200L);
        when(workflowMapper.selectById(5L)).thenReturn(workflow);

        assertThrows(BusinessException.class, () -> chatTargetService.validateTarget(100L, null, 5L));
    }
}
