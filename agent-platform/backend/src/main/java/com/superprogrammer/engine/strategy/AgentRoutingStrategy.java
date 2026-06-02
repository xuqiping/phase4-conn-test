package com.superprogrammer.engine.strategy;

import com.superprogrammer.agent.entity.Agent;
import com.superprogrammer.agent.mapper.AgentMapper;
import com.superprogrammer.engine.context.ExecutionContext;
import com.superprogrammer.engine.executor.SkillExecutor;
import com.superprogrammer.engine.router.AgentRouter;
import com.superprogrammer.engine.router.RoutingResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AgentRoutingStrategy implements ExecutionStrategy {

    private final AgentMapper agentMapper;
    private final AgentRouter agentRouter;
    private final SkillExecutor skillExecutor;

    @Override
    public String execute(ExecutionContext context, String userMessage) {
        Long agentId = context.getAgentId();
        log.info("Agent路由模式, agentId={}, message={}", agentId, userMessage);

        Agent agent = agentMapper.selectById(agentId);
        if (agent == null) {
            return "Agent不存在";
        }

        RoutingResult routing = agentRouter.route(agent, userMessage);
        if (routing.getSkillIds().isEmpty()) {
            return "未找到匹配的技能来处理您的请求";
        }

        log.info("Agent[{}]路由结果: skills={}", agent.getName(), routing.getSkillIds());

        context.addMessage("user", userMessage);
        context.getVariableStore().set("input", userMessage);

        StringBuilder result = new StringBuilder();
        for (Long skillId : routing.getSkillIds()) {
            String skillOutput = skillExecutor.executeSkill(skillId, context);
            result.append(skillOutput);
        }

        return result.toString();
    }
}
