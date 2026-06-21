package com.superprogrammer.runtime.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.runtime.dto.RuntimeNodeType;
import com.superprogrammer.runtime.dto.WorkflowDefinition;
import com.superprogrammer.workflow.dto.WorkflowDetailVO;
import com.superprogrammer.workflow.dto.WorkflowEdgeDTO;
import com.superprogrammer.workflow.dto.WorkflowNodeDTO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowDefinitionAssemblerTest {

    private final WorkflowDefinitionAssembler assembler = new WorkflowDefinitionAssembler(new ObjectMapper());

    @Test
    void assemble_convertsBasicWorkflowNodesAndEdges() {
        WorkflowDetailVO workflow = WorkflowDetailVO.builder()
                .id(10L)
                .name("基础流程")
                .nodes(List.of(
                        node("start-1", "start", "开始", null),
                        node("skill-1", "skill", "写作技能", "{\"skillId\":5}"),
                        node("end-1", "end", "结束", null)))
                .edges(List.of(
                        edge("start-1", "skill-1"),
                        edge("skill-1", "end-1")))
                .build();

        WorkflowDefinition definition = assembler.assemble(workflow);

        assertThat(definition.getVersion()).isEqualTo("2026-06-03");
        assertThat(definition.getWorkflowId()).isEqualTo(10L);
        assertThat(definition.getNodes()).extracting("type")
                .containsExactly(RuntimeNodeType.START, RuntimeNodeType.SKILL, RuntimeNodeType.END);
        assertThat(definition.getNodes().get(1).getConfig()).containsEntry("skillId", 5);
        assertThat(definition.getEdges()).hasSize(2);
        assertThat(definition.getEdges().get(0).getSource()).isEqualTo("start-1");
        assertThat(definition.getEdges().get(0).getTarget()).isEqualTo("skill-1");
    }

    @Test
    void assemble_preservesAgentAndWorkflowReferenceConfig() {
        WorkflowDetailVO workflow = WorkflowDetailVO.builder()
                .id(11L)
                .name("组合流程")
                .nodes(List.of(
                        node("agent-1", "agent_ref", "文案 Agent", "{\"agentId\":3}"),
                        node("workflow-1", "WORKFLOW_REF", "审核流程", "{\"workflowId\":7}")))
                .edges(List.of(edge("agent-1", "workflow-1")))
                .build();

        WorkflowDefinition definition = assembler.assemble(workflow);

        assertThat(definition.getNodes().get(0).getType()).isEqualTo(RuntimeNodeType.AGENT_REF);
        assertThat(definition.getNodes().get(0).getConfig()).containsEntry("agentId", 3);
        assertThat(definition.getNodes().get(1).getType()).isEqualTo(RuntimeNodeType.WORKFLOW_REF);
        assertThat(definition.getNodes().get(1).getConfig()).containsEntry("workflowId", 7);
    }

    @Test
    void assemble_preservesInputNodeConfig() {
        WorkflowDetailVO workflow = WorkflowDetailVO.builder()
                .id(16L)
                .name("input workflow")
                .nodes(List.of(
                        node("input-prompt", "input", "Prompt",
                                "{\"inputKey\":\"prompt\",\"inputType\":\"textarea\",\"required\":true}")))
                .edges(List.of())
                .build();

        WorkflowDefinition definition = assembler.assemble(workflow);

        assertThat(definition.getNodes().get(0).getType()).isEqualTo(RuntimeNodeType.INPUT);
        assertThat(definition.getNodes().get(0).getConfig())
                .containsEntry("inputKey", "prompt")
                .containsEntry("inputType", "textarea")
                .containsEntry("required", true);
    }

    @Test
    void assemble_rejectsInvalidNodeConfigJson() {
        WorkflowDetailVO workflow = WorkflowDetailVO.builder()
                .id(12L)
                .name("坏配置")
                .nodes(List.of(node("agent-1", "agent_ref", "文案 Agent", "{bad-json")))
                .edges(List.of())
                .build();

        assertThatThrownBy(() -> assembler.assemble(workflow))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("工作流节点配置不是合法 JSON");
    }

    @Test
    void assemble_rejectsAgentReferenceWithoutAgentId() {
        WorkflowDetailVO workflow = WorkflowDetailVO.builder()
                .id(13L)
                .name("缺少Agent引用")
                .nodes(List.of(node("agent-1", "agent_ref", "文案 Agent", "{}")))
                .edges(List.of())
                .build();

        assertThatThrownBy(() -> assembler.assemble(workflow))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("AGENT_REF 节点必须配置 agentId");
    }

    @Test
    void assemble_rejectsWorkflowReferenceWithoutWorkflowId() {
        WorkflowDetailVO workflow = WorkflowDetailVO.builder()
                .id(14L)
                .name("缺少Workflow引用")
                .nodes(List.of(node("workflow-1", "workflow_ref", "审核流程", "{}")))
                .edges(List.of())
                .build();

        assertThatThrownBy(() -> assembler.assemble(workflow))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("WORKFLOW_REF 节点必须配置 workflowId");
    }

    @Test
    void assemble_rejectsWorkflowReferenceToSelf() {
        WorkflowDetailVO workflow = WorkflowDetailVO.builder()
                .id(15L)
                .name("自引用流程")
                .nodes(List.of(node("workflow-1", "workflow_ref", "自引用", "{\"workflowId\":15}")))
                .edges(List.of())
                .build();

        assertThatThrownBy(() -> assembler.assemble(workflow))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("WORKFLOW_REF 节点不能引用当前工作流");
    }

    private WorkflowNodeDTO node(String nodeId, String type, String label, String config) {
        return WorkflowNodeDTO.builder()
                .nodeId(nodeId)
                .type(type)
                .label(label)
                .config(config)
                .build();
    }

    private WorkflowEdgeDTO edge(String source, String target) {
        return WorkflowEdgeDTO.builder()
                .sourceNodeId(source)
                .targetNodeId(target)
                .sourceHandle("next")
                .targetHandle("in")
                .label("下一步")
                .condition("true")
                .build();
    }
}
