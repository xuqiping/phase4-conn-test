package com.superprogrammer.engine.strategy;

import com.superprogrammer.agent.entity.Agent;
import com.superprogrammer.agent.mapper.AgentMapper;
import com.superprogrammer.engine.context.ExecutionContext;
import com.superprogrammer.engine.executor.SkillExecutor;
import com.superprogrammer.engine.router.AgentRouter;
import com.superprogrammer.engine.router.RoutingResult;
import com.superprogrammer.knowledge.dto.EvidenceResult;
import com.superprogrammer.knowledge.service.RagRetrievalService;
import com.superprogrammer.knowledge.service.RagScopeResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class AgentRoutingStrategy implements ExecutionStrategy {

    private final AgentMapper agentMapper;
    private final AgentRouter agentRouter;
    private final SkillExecutor skillExecutor;
    // 阶段5 RAG（AGENT 模式：证据经 firstStepConfigOverride 注入 step1 systemPrompt，
    // 因 LlmCallHandler 只读 config.systemPrompt 不读 messageHistory）
    private final RagScopeResolver ragScopeResolver;
    private final RagRetrievalService ragRetrievalService;

    @Override
    public String execute(ExecutionContext context, String userMessage) {
        Long agentId = context.getAgentId();
        // 安全审计 #6：chat 原文属高敏 PII，降 DEBUG（生产 INFO 不打）；排查需单降 logger 级别。
        log.debug("Agent路由模式, agentId={}, message.len={}", agentId, userMessage == null ? 0 : userMessage.length());

        Agent agent = agentMapper.selectById(agentId);
        if (agent == null) {
            return "Agent不存在";
        }

        // 阶段5 RAG：AGENT scope = agent_kb_bindings ∩ 用户权限（P4），同模型约束；受记忆模式门控
        EvidenceResult evidence = context.isRagEnabled()
                ? resolveAgentEvidence(agentId, context.getUserId(), userMessage) : null;
        // 修 #2：abstain 不再短路吐死句子。丢弃证据，照常路由到 skill/LLM 生成（不带知识库引用），
        // 让 AI 基于自身能力回答，而不是直接返回"未找到可访问的相关知识"。
        if (evidence != null && evidence.isAbstained()) {
            log.info("Agent[{}] 检索 abstain({}), 丢弃证据照常执行技能", agent.getName(), evidence.getAbstainReason());
            evidence = null;
        }

        RoutingResult routing = agentRouter.route(agent, userMessage, context.getUserId(), context.getModel());
        if (routing.getSkillIds().isEmpty()) {
            return "未找到匹配的技能来处理您的请求";
        }

        log.info("Agent[{}]路由结果: skills={}", agent.getName(), routing.getSkillIds());

        context.addMessage("user", userMessage);
        context.getVariableStore().set("input", userMessage);

        // 证据注入：首个 skill 的 step1 systemPrompt 前置证据（PREPEND，保留原人设）
        Map<String, Object> override = (evidence != null && evidence.getSystemPrompt() != null)
                ? Map.of("systemPrompt", evidence.getSystemPrompt())
                : Map.of();

        StringBuilder result = new StringBuilder();
        for (int i = 0; i < routing.getSkillIds().size(); i++) {
            Long skillId = routing.getSkillIds().get(i);
            // 仅首个 skill 注入证据（firstStepConfigOverride 只作用于 step1）
            String skillOutput = (i == 0 && !override.isEmpty())
                    ? skillExecutor.executeSkill(skillId, context, override)
                    : skillExecutor.executeSkill(skillId, context);
            result.append(skillOutput);
        }

        return result.toString();
    }

    /** AGENT 模式 RAG 证据；无可检索范围 → null（普通 Agent 执行）。 */
    private EvidenceResult resolveAgentEvidence(Long agentId, Long userId, String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        boolean admin = isAdmin();
        List<Long> effective = ragScopeResolver.resolveEffectiveKbs(
                "AGENT", null, agentId, null, userId, admin);
        if (effective.isEmpty()) {
            return null;
        }
        return ragRetrievalService.retrieveEvidence(effective, query, userId, admin);
    }

    private boolean isAdmin() {
        try {
            org.springframework.security.core.Authentication auth =
                    org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
            return auth != null && auth.getAuthorities().stream()
                    .map(org.springframework.security.core.GrantedAuthority::getAuthority)
                    .anyMatch(a -> "ROLE_admin".equalsIgnoreCase(a) || "ROLE_ADMIN".equalsIgnoreCase(a));
        } catch (Exception e) {
            return false;
        }
    }
}
