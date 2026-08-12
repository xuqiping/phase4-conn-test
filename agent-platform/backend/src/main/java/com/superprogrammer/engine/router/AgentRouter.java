package com.superprogrammer.engine.router;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.agent.entity.Agent;
import com.superprogrammer.agent.entity.Skill;
import com.superprogrammer.agent.mapper.SkillMapper;
import com.superprogrammer.llm.LlmGateway;
import com.superprogrammer.llm.dto.LlmMessage;
import com.superprogrammer.llm.dto.LlmRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class AgentRouter {

    private final SkillMapper skillMapper;
    private final LlmGateway llmGateway;
    private final ObjectMapper objectMapper;

    public RoutingResult route(Agent agent, String userMessage, Long userId) {
        return route(agent, userMessage, userId, null);
    }

    public RoutingResult route(Agent agent, String userMessage, Long userId, String selectedModel) {
        List<Long> ruleMatched = matchByRules(agent, userMessage);
        if (!ruleMatched.isEmpty()) {
            log.info("Agent[{}] 规则匹配成功, skills={}", agent.getName(), ruleMatched);
            return RoutingResult.builder().skillIds(ruleMatched).build();
        }

        log.info("Agent[{}] 规则未命中，使用LLM意图识别", agent.getName());
        return matchByLlm(agent, userMessage, userId, selectedModel);
    }

    private List<Long> matchByRules(Agent agent, String userMessage) {
        try {
            String configJson = agent.getConfig();
            if (configJson == null || configJson.isBlank()) return List.of();

            JsonNode root = objectMapper.readTree(configJson);
            JsonNode rules = root.at("/routingRules");
            if (rules.isMissingNode() || !rules.isArray()) return List.of();

            for (JsonNode rule : rules) {
                JsonNode keywords = rule.at("/keywords");
                if (!keywords.isArray()) continue;

                for (JsonNode kw : keywords) {
                    if (userMessage.contains(kw.asText())) {
                        JsonNode ids = rule.at("/skillIds");
                        List<Long> skillIds = new ArrayList<>();
                        for (JsonNode id : ids) {
                            skillIds.add(id.asLong());
                        }
                        return skillIds;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("解析Agent[{}]路由规则失败", agent.getName(), e);
        }
        return List.of();
    }

    private RoutingResult matchByLlm(Agent agent, String userMessage, Long userId, String selectedModel) {
        LambdaQueryWrapper<Skill> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Skill::getAgentId, agent.getId())
               .eq(Skill::getDeleted, 0)
               .orderByAsc(Skill::getSortOrder);
        List<Skill> allSkills = skillMapper.selectList(wrapper);

        if (allSkills.isEmpty()) {
            return RoutingResult.builder().skillIds(List.of()).build();
        }

        String catalog = allSkills.stream()
                .map(s -> String.format("ID:%d 名称:%s 描述:%s", s.getId(), s.getName(),
                        s.getDescription() != null ? s.getDescription() : ""))
                .collect(Collectors.joining("\n"));

        String prompt = String.format(
                "你是Agent「%s」的路由器。用户说：「%s」\n\n可用技能：\n%s\n\n" +
                "请从上述技能中选择最匹配的，只返回技能ID的JSON数组，例如[1,3]。不要其他文字。",
                agent.getName(), userMessage, catalog);

        LlmRequest request = LlmRequest.builder()
                .model(selectedModel != null && !selectedModel.isBlank()
                        ? selectedModel.trim() : readConfiguredModel(agent))
                .messages(List.of(LlmMessage.builder().role("user").content(prompt).build()))
                .temperature(0.3)
                .build();

        var response = llmGateway.chat(request, userId);
        List<Long> skillIds = parseSkillIds(response.getContent());

        return RoutingResult.builder()
                .skillIds(skillIds)
                .executionPlan(response.getContent())
                .build();
    }

    /** Agent 可显式配置路由模型；为空时由 LlmGateway 使用管理员默认。 */
    private String readConfiguredModel(Agent agent) {
        try {
            if (agent.getConfig() == null || agent.getConfig().isBlank()) return null;
            String model = objectMapper.readTree(agent.getConfig()).at("/model").asText(null);
            return model == null || model.isBlank() ? null : model.trim();
        } catch (Exception e) {
            log.warn("解析Agent[{}]模型配置失败，转交管理员默认模型解析", agent.getName());
            return null;
        }
    }

    private List<Long> parseSkillIds(String llmOutput) {
        try {
            String trimmed = llmOutput.trim();
            int start = trimmed.indexOf('[');
            int end = trimmed.lastIndexOf(']');
            if (start >= 0 && end > start) {
                String jsonArr = trimmed.substring(start, end + 1);
                JsonNode arr = objectMapper.readTree(jsonArr);
                List<Long> ids = new ArrayList<>();
                for (JsonNode node : arr) {
                    ids.add(node.asLong());
                }
                return ids;
            }
        } catch (Exception e) {
            log.warn("解析LLM返回的技能ID失败: {}", llmOutput, e);
        }
        return List.of();
    }
}
