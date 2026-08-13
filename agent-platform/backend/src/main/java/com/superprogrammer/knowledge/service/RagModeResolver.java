package com.superprogrammer.knowledge.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.agent.entity.Agent;
import com.superprogrammer.agent.mapper.AgentMapper;
import com.superprogrammer.execution.service.ExecutionLogService;
import com.superprogrammer.system.service.SystemSettingService;
import com.superprogrammer.workflow.entity.Workflow;
import com.superprogrammer.workflow.mapper.WorkflowMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 记忆模式开关优先级解析（4 层）：session > agent/workflow(按 mode) > global。
 * 门控 RAG + 用户记忆 + 预留 answer_cache。默认关（opt-in）。
 *
 * <p>三态：session/agent/workflow 字段 null=继承；非 null=覆盖。全空 → global。
 * <p>WORKFLOW 检索节点回调无 session 上下文 → 仅 workflow + global（session 覆盖不进工作流执行）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RagModeResolver {

    private final SystemSettingService systemSettingService;
    private final AgentMapper agentMapper;
    private final WorkflowMapper workflowMapper;
    private final ExecutionLogService executionLogService;
    private final ObjectMapper objectMapper;
    private final com.superprogrammer.knowledge.migration.RagRolloutService ragRolloutService;

    public boolean useChallenger(Long kbId, Long userId) {
        return kbId != null && userId != null && ragRolloutService.useChallenger(kbId, userId);
    }

    /**
     * 解析当前会话是否开启记忆模式。
     *
     * @param mode             CHAT / AGENT / WORKFLOW
     * @param sessionRagEnabled 会话级开关（null=继承）
     * @param agentId          AGENT 模式
     * @param workflowId       WORKFLOW 模式
     * @return true=启用 RAG/记忆；false=纯裸
     */
    public boolean resolve(String mode, Boolean sessionRagEnabled, Long agentId, Long workflowId) {
        // 1. 会话最高
        if (sessionRagEnabled != null) {
            return sessionRagEnabled;
        }
        // 2. 按模式取 agent/workflow 覆盖
        if (mode != null) {
            String m = mode.toUpperCase();
            if ("AGENT".equals(m) && agentId != null) {
                Boolean agentFlag = agentRagEnabled(agentId);
                if (agentFlag != null) {
                    return agentFlag;
                }
            } else if ("WORKFLOW".equals(m) && workflowId != null) {
                Boolean wfFlag = workflowRagEnabled(workflowId);
                if (wfFlag != null) {
                    return wfFlag;
                }
            }
        }
        // 3. global 兜底（默认 false）
        return systemSettingService.getRagMemoryEnabled();
    }

    /**
     * RETRIEVAL 节点回调专用：回调无 session，按 executionId → workflowId 解析（workflow + global）。
     */
    public boolean resolveForWorkflowCallback(Long executionId) {
        Long workflowId = null;
        if (executionId != null) {
            try {
                workflowId = executionLogService.getExecutionLog(executionId).getWorkflowId();
            } catch (Exception e) {
                log.warn("retrieval 回调解析 workflowId 失败 executionId={}: {}", executionId, e.getMessage());
            }
        }
        return resolve("WORKFLOW", null, null, workflowId);
    }

    /** Agent.config.ragEnabled 三态（null=继承）。 */
    private Boolean agentRagEnabled(Long agentId) {
        try {
            Agent agent = agentMapper.selectById(agentId);
            if (agent == null || agent.getConfig() == null || agent.getConfig().isBlank()) {
                return null;
            }
            JsonNode node = objectMapper.readTree(agent.getConfig());
            JsonNode flag = node.path("ragEnabled");
            return flag.isMissingNode() || flag.isNull() ? null : flag.asBoolean();
        } catch (Exception e) {
            log.warn("解析 Agent.config.ragEnabled 失败 agentId={}: {}", agentId, e.getMessage());
            return null;
        }
    }

    /** workflow.rag_enabled 三态（null=继承）。 */
    private Boolean workflowRagEnabled(Long workflowId) {
        try {
            Workflow wf = workflowMapper.selectById(workflowId);
            return wf == null ? null : wf.getRagEnabled();
        } catch (Exception e) {
            log.warn("读取 workflow.rag_enabled 失败 workflowId={}: {}", workflowId, e.getMessage());
            return null;
        }
    }
}
