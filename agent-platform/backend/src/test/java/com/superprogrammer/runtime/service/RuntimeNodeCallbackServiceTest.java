package com.superprogrammer.runtime.service;

import com.superprogrammer.agent.entity.Agent;
import com.superprogrammer.agent.entity.Skill;
import com.superprogrammer.agent.mapper.AgentMapper;
import com.superprogrammer.agent.mapper.SkillMapper;
import com.superprogrammer.agent.service.AgentPermissionService;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.engine.executor.SkillExecutor;
import com.superprogrammer.engine.router.AgentRouter;
import com.superprogrammer.engine.router.RoutingResult;
import com.superprogrammer.runtime.dto.RuntimeNodeCallbackRequest;
import com.superprogrammer.runtime.dto.RuntimeNodeCallbackResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RuntimeNodeCallbackServiceTest {

    @Mock
    private SkillExecutor skillExecutor;

    @Mock
    private AgentMapper agentMapper;

    @Mock
    private SkillMapper skillMapper;

    @Mock
    private AgentRouter agentRouter;

    @Mock
    private AgentPermissionService agentPermissionService;

    @Mock
    private com.superprogrammer.knowledge.service.RagScopeResolver ragScopeResolver;

    @Mock
    private com.superprogrammer.knowledge.service.RagRetrievalService ragRetrievalService;

    @Mock
    private com.superprogrammer.knowledge.service.RagModeResolver ragModeResolver;

    @Test
    void executeNode_runsSkillExecutorForSkillNode() {
        RuntimeNodeCallbackService service = new RuntimeNodeCallbackService(skillExecutor, agentMapper, skillMapper, agentRouter, agentPermissionService, ragScopeResolver, ragRetrievalService, ragModeResolver);
        Skill skill = new Skill();
        skill.setId(12L);
        skill.setAgentId(3L);
        Agent agent = new Agent();
        agent.setId(3L);
        agent.setStatus("PUBLISHED");
        when(skillMapper.selectById(12L)).thenReturn(skill);
        when(agentMapper.selectById(3L)).thenReturn(agent);
        when(agentPermissionService.canUse(3L, 7L, false)).thenReturn(true);
        when(skillExecutor.executeSkill(eq(12L), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyMap()))
                .thenReturn("skill output");

        RuntimeNodeCallbackResponse response = service.executeNode(RuntimeNodeCallbackRequest.builder()
                .executionId("1001")
                .rootExecutionId("1001")
                .nodeId("skill-1")
                .sourceType("SKILL")
                .sourceId(12L)
                .userId(7L)
                .input(Map.of("message", "hello", "topic", "runtime"))
                .traceId("trace-1")
                .build());

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getSelectedSkillIds()).containsExactly(12L);
        assertThat(response.getOutput()).containsEntry("text", "skill output");
        assertThat(response.getStepOutputs()).hasSize(1);
        assertThat(response.getStepOutputs().get(0)).containsEntry("skillId", 12L);
        assertThat(response.getMetadata()).containsEntry("traceId", "trace-1");

        ArgumentCaptor<com.superprogrammer.engine.context.ExecutionContext> contextCaptor =
                ArgumentCaptor.forClass(com.superprogrammer.engine.context.ExecutionContext.class);
        verify(skillExecutor).executeSkill(eq(12L), contextCaptor.capture(), org.mockito.ArgumentMatchers.anyMap());
        assertThat(contextCaptor.getValue().getExecutionId()).isEqualTo(1001L);
        assertThat(contextCaptor.getValue().getUserId()).isEqualTo(7L);
        assertThat(contextCaptor.getValue().getVariableStore().get("input")).isEqualTo("hello");
        assertThat(contextCaptor.getValue().getVariableStore().get("topic")).isEqualTo("runtime");
    }

    @Test
    void executeNode_mapsPromptInputToTemplateInputWhenMessageIsAbsent() {
        RuntimeNodeCallbackService service = new RuntimeNodeCallbackService(skillExecutor, agentMapper, skillMapper, agentRouter, agentPermissionService, ragScopeResolver, ragRetrievalService, ragModeResolver);
        Skill skill = new Skill();
        skill.setId(12L);
        skill.setAgentId(3L);
        Agent agent = new Agent();
        agent.setId(3L);
        agent.setStatus("PUBLISHED");
        when(skillMapper.selectById(12L)).thenReturn(skill);
        when(agentMapper.selectById(3L)).thenReturn(agent);
        when(agentPermissionService.canUse(3L, 7L, false)).thenReturn(true);
        when(skillExecutor.executeSkill(eq(12L), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyMap()))
                .thenReturn("skill output");

        service.executeNode(RuntimeNodeCallbackRequest.builder()
                .executionId("1001")
                .nodeId("skill-1")
                .sourceType("SKILL")
                .sourceId(12L)
                .userId(7L)
                .input(Map.of("prompt", "prompt value", "summary", "upstream summary"))
                .build());

        ArgumentCaptor<com.superprogrammer.engine.context.ExecutionContext> contextCaptor =
                ArgumentCaptor.forClass(com.superprogrammer.engine.context.ExecutionContext.class);
        verify(skillExecutor).executeSkill(eq(12L), contextCaptor.capture(), org.mockito.ArgumentMatchers.anyMap());
        assertThat(contextCaptor.getValue().getVariableStore().get("input")).isEqualTo("prompt value");
        assertThat(contextCaptor.getValue().getVariableStore().get("prompt")).isEqualTo("prompt value");
        assertThat(contextCaptor.getValue().getVariableStore().get("summary")).isEqualTo("upstream summary");
    }

    @Test
    void executeNode_passesNodePromptConfigAsFirstStepOverride() {
        RuntimeNodeCallbackService service = new RuntimeNodeCallbackService(skillExecutor, agentMapper, skillMapper, agentRouter, agentPermissionService, ragScopeResolver, ragRetrievalService, ragModeResolver);
        Skill skill = new Skill();
        skill.setId(12L);
        skill.setAgentId(3L);
        Agent agent = new Agent();
        agent.setId(3L);
        agent.setStatus("PUBLISHED");
        when(skillMapper.selectById(12L)).thenReturn(skill);
        when(agentMapper.selectById(3L)).thenReturn(agent);
        when(agentPermissionService.canUse(3L, 7L, false)).thenReturn(true);
        when(skillExecutor.executeSkill(eq(12L), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyMap()))
                .thenReturn("skill output");

        service.executeNode(RuntimeNodeCallbackRequest.builder()
                .executionId("1001")
                .nodeId("skill-1")
                .sourceType("SKILL")
                .sourceId(12L)
                .userId(7L)
                .input(Map.of("message", "hello"))
                .metadata(Map.of("nodeConfig", Map.of(
                        "systemPrompt", "system",
                        "promptTemplate", "user {{input}}",
                        "outputKey", "summary",
                        "temperature", 0.2)))
                .build());

        ArgumentCaptor<Map<String, Object>> overrideCaptor = ArgumentCaptor.forClass(Map.class);
        verify(skillExecutor).executeSkill(eq(12L), org.mockito.ArgumentMatchers.any(), overrideCaptor.capture());
        assertThat(overrideCaptor.getValue()).containsEntry("systemPrompt", "system");
        assertThat(overrideCaptor.getValue()).containsEntry("promptTemplate", "user {{input}}");
        assertThat(overrideCaptor.getValue()).containsEntry("outputKey", "summary");
        assertThat(overrideCaptor.getValue()).containsEntry("temperature", 0.2);
    }

    @Test
    void executeNode_appliesNodeInputMappingsWithScopedReferences() {
        RuntimeNodeCallbackService service = new RuntimeNodeCallbackService(skillExecutor, agentMapper, skillMapper, agentRouter, agentPermissionService, ragScopeResolver, ragRetrievalService, ragModeResolver);
        Skill skill = new Skill();
        skill.setId(12L);
        skill.setAgentId(3L);
        Agent agent = new Agent();
        agent.setId(3L);
        agent.setStatus("PUBLISHED");
        when(skillMapper.selectById(12L)).thenReturn(skill);
        when(agentMapper.selectById(3L)).thenReturn(agent);
        when(agentPermissionService.canUse(3L, 7L, false)).thenReturn(true);
        when(skillExecutor.executeSkill(eq(12L), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyMap()))
                .thenReturn("skill output");

        RuntimeNodeCallbackResponse response = service.executeNode(RuntimeNodeCallbackRequest.builder()
                .executionId("1001")
                .nodeId("skill-1")
                .sourceType("SKILL")
                .sourceId(12L)
                .userId(7L)
                .input(Map.of("summaryA.summary", "上游摘要"))
                .metadata(Map.of("nodeConfig", Map.of(
                        "inputMappings", Map.of("input", "{{summaryA.summary}}"),
                        "outputKey", "acceptance")))
                .build());

        ArgumentCaptor<com.superprogrammer.engine.context.ExecutionContext> contextCaptor =
                ArgumentCaptor.forClass(com.superprogrammer.engine.context.ExecutionContext.class);
        verify(skillExecutor).executeSkill(eq(12L), contextCaptor.capture(), org.mockito.ArgumentMatchers.anyMap());
        assertThat(contextCaptor.getValue().getVariableStore().get("input")).isEqualTo("上游摘要");
        assertThat(response.getOutput()).containsEntry("outputKey", "acceptance");
        assertThat(response.getStepOutputs().get(0)).containsEntry("outputKey", "acceptance");
    }

    @Test
    void executeNode_routesAgentRefAndRunsSelectedSkills() {
        RuntimeNodeCallbackService service = new RuntimeNodeCallbackService(skillExecutor, agentMapper, skillMapper, agentRouter, agentPermissionService, ragScopeResolver, ragRetrievalService, ragModeResolver);
        Agent agent = new Agent();
        agent.setId(3L);
        agent.setName("writer");
        agent.setStatus("PUBLISHED");
        when(agentMapper.selectById(3L)).thenReturn(agent);
        when(agentPermissionService.canUse(3L, 7L, false)).thenReturn(true);
        when(agentRouter.route(agent, "write docs"))
                .thenReturn(RoutingResult.builder().skillIds(java.util.List.of(12L, 13L)).executionPlan("[12,13]").build());
        when(skillExecutor.executeSkill(eq(12L), org.mockito.ArgumentMatchers.any()))
                .thenReturn("outline");
        when(skillExecutor.executeSkill(eq(13L), org.mockito.ArgumentMatchers.any()))
                .thenReturn("final docs");

        RuntimeNodeCallbackResponse response = service.executeNode(RuntimeNodeCallbackRequest.builder()
                .executionId("1001")
                .rootExecutionId("1001")
                .nodeId("agent-1")
                .sourceType("AGENT")
                .sourceId(3L)
                .userId(7L)
                .input(Map.of("message", "write docs"))
                .traceId("trace-1")
                .build());

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getSelectedSkillIds()).containsExactly(12L, 13L);
        assertThat(response.getOutput()).containsEntry("text", "final docs");
        assertThat(response.getOutput()).containsEntry("agentId", 3L);
        assertThat(response.getOutput()).containsEntry("agentName", "writer");
        assertThat(response.getStepOutputs()).hasSize(2);
        assertThat(response.getStepOutputs().get(0)).containsEntry("skillId", 12L).containsEntry("output", "outline");
        assertThat(response.getStepOutputs().get(1)).containsEntry("skillId", 13L).containsEntry("output", "final docs");

        verify(agentRouter).route(agent, "write docs");
        verify(skillExecutor).executeSkill(eq(12L), org.mockito.ArgumentMatchers.any());
        verify(skillExecutor).executeSkill(eq(13L), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void executeNode_rejectsSkillWhenOwningAgentIsNotPublishedAndUserIsNotOwner() {
        RuntimeNodeCallbackService service = new RuntimeNodeCallbackService(skillExecutor, agentMapper, skillMapper, agentRouter, agentPermissionService, ragScopeResolver, ragRetrievalService, ragModeResolver);
        Skill skill = new Skill();
        skill.setId(12L);
        skill.setAgentId(3L);
        Agent agent = new Agent();
        agent.setId(3L);
        agent.setStatus("DRAFT");
        agent.setCreatedBy(99L);
        when(skillMapper.selectById(12L)).thenReturn(skill);
        when(agentMapper.selectById(3L)).thenReturn(agent);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.executeNode(RuntimeNodeCallbackRequest.builder()
                        .executionId("1001")
                        .nodeId("skill-1")
                        .sourceType("SKILL")
                        .sourceId(12L)
                        .userId(7L)
                        .input(Map.of("message", "hello"))
                        .build()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Agent is not executable");
    }

    @Test
    void executeNode_allowsDraftAgentForOwner() {
        RuntimeNodeCallbackService service = new RuntimeNodeCallbackService(skillExecutor, agentMapper, skillMapper, agentRouter, agentPermissionService, ragScopeResolver, ragRetrievalService, ragModeResolver);
        Agent agent = new Agent();
        agent.setId(3L);
        agent.setName("writer");
        agent.setStatus("DRAFT");
        agent.setCreatedBy(7L);
        when(agentMapper.selectById(3L)).thenReturn(agent);
        when(agentRouter.route(agent, "write docs"))
                .thenReturn(RoutingResult.builder().skillIds(java.util.List.of(12L)).build());
        when(skillExecutor.executeSkill(eq(12L), org.mockito.ArgumentMatchers.any()))
                .thenReturn("owner draft output");

        RuntimeNodeCallbackResponse response = service.executeNode(RuntimeNodeCallbackRequest.builder()
                .executionId("1001")
                .nodeId("agent-1")
                .sourceType("AGENT")
                .sourceId(3L)
                .userId(7L)
                .input(Map.of("message", "write docs"))
                .build());

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getOutput()).containsEntry("text", "owner draft output");
    }

    @Test
    void executeNode_rejectsPublishedAgentWhenUserLacksObjectUsePermission() {
        RuntimeNodeCallbackService service = new RuntimeNodeCallbackService(skillExecutor, agentMapper, skillMapper, agentRouter, agentPermissionService, ragScopeResolver, ragRetrievalService, ragModeResolver);
        Agent agent = new Agent();
        agent.setId(3L);
        agent.setName("writer");
        agent.setStatus("PUBLISHED");
        agent.setCreatedBy(99L);
        when(agentMapper.selectById(3L)).thenReturn(agent);
        when(agentPermissionService.canUse(3L, 7L, false)).thenReturn(false);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.executeNode(RuntimeNodeCallbackRequest.builder()
                        .executionId("1001")
                        .nodeId("agent-1")
                        .sourceType("AGENT")
                        .sourceId(3L)
                        .userId(7L)
                        .input(Map.of("message", "write docs"))
                        .build()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Agent is not executable");
    }

    @Test
    void executeNode_rendersRetrievalQueryTemplateFromUpstreamAliasOutput() {
        // 脱离点 1：检索节点 query 支持 {{上游别名.输出键}}，sidecar 已把上游输出按 alias.key 合并进 input
        RuntimeNodeCallbackService service = new RuntimeNodeCallbackService(skillExecutor, agentMapper, skillMapper, agentRouter, agentPermissionService, ragScopeResolver, ragRetrievalService, ragModeResolver);
        when(ragModeResolver.resolveForWorkflowCallback(1001L)).thenReturn(true);
        when(ragScopeResolver.resolveNodeKbs(anyList(), eq(7L))).thenReturn(List.of(5L));
        when(ragRetrievalService.retrieveEvidence(eq(List.of(5L)), eq("怎么退款"), eq(7L), eq(false)))
                .thenReturn(com.superprogrammer.knowledge.dto.EvidenceResult.builder()
                        .abstained(false)
                        .systemPrompt("[1] 退款证据")
                        .build());

        RuntimeNodeCallbackResponse response = service.executeNode(RuntimeNodeCallbackRequest.builder()
                .executionId("1001")
                .nodeId("retrieval-1")
                .sourceType("RETRIEVAL")
                .userId(7L)
                .input(Map.of("start.message", "怎么退款"))
                .metadata(Map.of("nodeConfig", Map.of(
                        "query", "{{start.message}}",
                        "kbIds", List.of(5))))
                .build());

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getOutput()).containsEntry("text", "[1] 退款证据");
        assertThat(response.getOutput()).containsEntry("abstained", false);
        verify(ragRetrievalService).retrieveEvidence(eq(List.of(5L)), eq("怎么退款"), eq(7L), eq(false));
    }

    @Test
    void executeNode_fallsBackToUpstreamMessageWhenRetrievalQueryBlank() {
        // 回归保护：query 留空时回退到上游 input/message（既有兜底行为不变）
        RuntimeNodeCallbackService service = new RuntimeNodeCallbackService(skillExecutor, agentMapper, skillMapper, agentRouter, agentPermissionService, ragScopeResolver, ragRetrievalService, ragModeResolver);
        when(ragModeResolver.resolveForWorkflowCallback(1001L)).thenReturn(true);
        when(ragScopeResolver.resolveNodeKbs(anyList(), eq(7L))).thenReturn(List.of(5L));
        when(ragRetrievalService.retrieveEvidence(eq(List.of(5L)), eq("fallback question"), eq(7L), eq(false)))
                .thenReturn(com.superprogrammer.knowledge.dto.EvidenceResult.builder()
                        .abstained(false)
                        .systemPrompt("[1] 证据")
                        .build());

        service.executeNode(RuntimeNodeCallbackRequest.builder()
                .executionId("1001")
                .nodeId("retrieval-1")
                .sourceType("RETRIEVAL")
                .userId(7L)
                .input(Map.of("message", "fallback question"))
                .metadata(Map.of("nodeConfig", Map.of(
                        "query", "",
                        "kbIds", List.of(5))))
                .build());

        verify(ragRetrievalService).retrieveEvidence(eq(List.of(5L)), eq("fallback question"), eq(7L), eq(false));
    }
}
