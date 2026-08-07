package com.superprogrammer.runtime.service;

import com.superprogrammer.agent.entity.Agent;
import com.superprogrammer.agent.entity.Skill;
import com.superprogrammer.agent.mapper.AgentMapper;
import com.superprogrammer.agent.mapper.SkillMapper;
import com.superprogrammer.agent.service.AgentPermissionService;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.engine.context.ExecutionContext;
import com.superprogrammer.engine.executor.SkillExecutor;
import com.superprogrammer.engine.router.AgentRouter;
import com.superprogrammer.engine.router.RoutingResult;
import com.superprogrammer.execution.entity.ExecutionLog;
import com.superprogrammer.execution.service.ExecutionLogService;
import com.superprogrammer.knowledge.dto.EvidenceResult;
import com.superprogrammer.knowledge.service.RagRetrievalService;
import com.superprogrammer.knowledge.service.RagScopeResolver;
import com.superprogrammer.runtime.dto.RuntimeNodeCallbackRequest;
import com.superprogrammer.runtime.dto.RuntimeNodeCallbackResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class RuntimeNodeCallbackService {

    private final SkillExecutor skillExecutor;
    private final AgentMapper agentMapper;
    private final SkillMapper skillMapper;
    private final AgentRouter agentRouter;
    private final AgentPermissionService agentPermissionService;
    // 阶段5 RAG 检索节点（v6 §2.4：sidecar 遇 RETRIEVAL 节点回调 Java）
    private final RagScopeResolver ragScopeResolver;
    private final RagRetrievalService ragRetrievalService;
    private final com.superprogrammer.knowledge.service.RagModeResolver ragModeResolver;
    /** 安全审计 #1：executionId → triggeredBy 反查（Java 自己写的 execution_logs，可信）。 */
    private final ExecutionLogService executionLogService;

    public RuntimeNodeCallbackResponse executeNode(RuntimeNodeCallbackRequest request) {
        // 安全审计 #1：userId 禁止信任请求体（此前调用方可填受害者 ID 越权检索他人 KB / 触发他人 Agent）。
        // 改由 executionId → execution_logs.triggeredBy 反查；与 body.userId 不一致 → 采用反查值，丢弃 body 值。
        Long trustedUserId = resolveTrustedUserId(request);
        if (trustedUserId == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "回调缺少有效 executionId，无法解析执行归属（拒绝越权回调）");
        }
        if (request.getUserId() != null && !request.getUserId().equals(trustedUserId)) {
            log.warn("回调 userId 与 executionId 归属不一致：body={} triggeredBy={} → 采用反查值",
                    request.getUserId(), trustedUserId);
        }
        request.setUserId(trustedUserId);

        if ("SKILL".equalsIgnoreCase(request.getSourceType())) {
            return executeSkill(request);
        }
        if ("AGENT".equalsIgnoreCase(request.getSourceType())) {
            return executeAgent(request);
        }
        if ("RETRIEVAL".equalsIgnoreCase(request.getSourceType())) {
            return executeRetrieval(request);
        }
        return RuntimeNodeCallbackResponse.builder()
                .success(false)
                .error("Runtime callback executor is not implemented for sourceType=" + request.getSourceType())
                .metadata(Map.of(
                        "executionId", request.getExecutionId(),
                        "nodeId", request.getNodeId(),
                        "sourceType", request.getSourceType()))
                .build();
    }

    /** v6 §2.4 检索节点：节点 config kbIds ∩ 用户权限（P4）→ retrieveEvidence → 证据供下游节点。受记忆模式门控。 */
    private RuntimeNodeCallbackResponse executeRetrieval(RuntimeNodeCallbackRequest request) {
        // 记忆模式门控（V26）：workflow.rag_enabled + global（回调无 session）
        boolean ragOn = ragModeResolver.resolveForWorkflowCallback(parseLong(request.getExecutionId()));
        if (!ragOn) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("text", "记忆模式未开启，跳过检索。");
            out.put("abstained", true);
            return RuntimeNodeCallbackResponse.builder().success(true).output(out).metadata(callbackMetadata(request)).build();
        }
        Map<String, Object> config = nodeConfig(request);
        List<Long> kbIds = extractKbIds(config, request.getSourceId());
        // 查询词支持 {{上游别名.输出键}} / {{平铺键}} 模板渲染（与 SKILL/AGENT_REF 一致）：
        // sidecar 的 callback_input 已把上游节点输出按「别名.输出键」与平铺输出键合并进 request.input。
        String query = renderQuery(stringValue(config.get("query")), request.getInput());
        if (query == null || query.isBlank()) {
            query = inputMessage(request);   // 回退到上游输入（input/message/prompt/text）
        }
        Map<String, Object> output = new LinkedHashMap<>();
        if (query == null || query.isBlank()) {
            output.put("text", "检索节点未收到查询输入。");
            output.put("abstained", true);
            return RuntimeNodeCallbackResponse.builder().success(true).output(output).metadata(callbackMetadata(request)).build();
        }
        List<Long> effective = ragScopeResolver.resolveNodeKbs(kbIds, request.getUserId());
        if (effective.isEmpty()) {
            output.put("text", "未配置可访问的知识库范围。");
            output.put("abstained", true);
            return RuntimeNodeCallbackResponse.builder().success(true).output(output).metadata(callbackMetadata(request)).build();
        }
        EvidenceResult ev = ragRetrievalService.retrieveEvidence(effective, query, request.getUserId(), false);
        if (ev.isAbstained()) {
            output.put("text", ev.getAnswer() == null ? "" : ev.getAnswer());
            output.put("abstained", true);
        } else {
            output.put("text", ev.getSystemPrompt());   // 证据上下文（[n] 标注）供下游 LLM 节点
            output.put("abstained", false);
            if (ev.getInjectedIndexes() != null && !ev.getInjectedIndexes().isEmpty()) {
                output.put("injectedIndexes", ev.getInjectedIndexes());
            }
        }
        return RuntimeNodeCallbackResponse.builder()
                .success(true)
                .output(output)
                .metadata(callbackMetadata(request))
                .build();
    }

    @SuppressWarnings("unchecked")
    private List<Long> extractKbIds(Map<String, Object> config, Long sourceIdFallback) {
        List<Long> kbIds = new ArrayList<>();
        Object kbIdsRaw = config.get("kbIds");
        if (kbIdsRaw instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Number n) {
                    kbIds.add(n.longValue());
                }
            }
        }
        Object kbIdRaw = config.get("kbId");
        if (kbIdRaw instanceof Number n) {
            kbIds.add(n.longValue());
        }
        if (kbIds.isEmpty() && sourceIdFallback != null) {
            kbIds.add(sourceIdFallback);   // sidecar resolve_source 把 kbId 作 sourceId
        }
        return kbIds;
    }

    private RuntimeNodeCallbackResponse executeAgent(RuntimeNodeCallbackRequest request) {
        Agent agent = agentMapper.selectById(request.getSourceId());
        if (agent == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Agent not found: " + request.getSourceId());
        }
        assertAgentExecutable(agent, request.getUserId());
        String message = inputMessage(request);
        RoutingResult routingResult = agentRouter.route(agent, message, request.getUserId());
        List<Long> skillIds = routingResult.getSkillIds() == null ? List.of() : routingResult.getSkillIds();
        List<Map<String, Object>> stepOutputs = new ArrayList<>();
        String lastOutput = "";
        for (Long skillId : skillIds) {
            ExecutionContext context = buildContext(request);
            context.getVariableStore().set("agentId", String.valueOf(agent.getId()));
            context.getVariableStore().set("agentName", agent.getName() == null ? "" : agent.getName());
            lastOutput = skillExecutor.executeSkill(skillId, context);
            stepOutputs.add(Map.of(
                    "skillId", skillId,
                    "output", lastOutput));
        }
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("text", lastOutput);
        output.put("agentId", agent.getId());
        output.put("agentName", agent.getName());
        if (routingResult.getExecutionPlan() != null) {
            output.put("executionPlan", routingResult.getExecutionPlan());
        }
        return RuntimeNodeCallbackResponse.builder()
                .success(true)
                .selectedSkillIds(skillIds)
                .stepOutputs(stepOutputs)
                .output(output)
                .metadata(callbackMetadata(request))
                .build();
    }

    private RuntimeNodeCallbackResponse executeSkill(RuntimeNodeCallbackRequest request) {
        Skill skill = skillMapper.selectById(request.getSourceId());
        if (skill == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Skill not found: " + request.getSourceId());
        }
        Agent ownerAgent = agentMapper.selectById(skill.getAgentId());
        if (ownerAgent == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Skill owner Agent not found: " + skill.getAgentId());
        }
        assertAgentExecutable(ownerAgent, request.getUserId());
        ExecutionContext context = buildContext(request);

        Map<String, Object> override = promptOverride(request);
        String output = skillExecutor.executeSkill(request.getSourceId(), context, override);
        String outputKey = stringValue(override.get("outputKey"));
        Map<String, Object> outputMap = new LinkedHashMap<>();
        outputMap.put("text", output);
        if (outputKey != null && !outputKey.isBlank()) {
            outputMap.put("outputKey", outputKey);
        }
        Map<String, Object> stepOutput = new LinkedHashMap<>();
        stepOutput.put("skillId", request.getSourceId());
        stepOutput.put("output", output);
        if (outputKey != null && !outputKey.isBlank()) {
            stepOutput.put("outputKey", outputKey);
        }
        return RuntimeNodeCallbackResponse.builder()
                .success(true)
                .selectedSkillIds(List.of(request.getSourceId()))
                .stepOutputs(List.of(stepOutput))
                .output(outputMap)
                .metadata(callbackMetadata(request))
                .build();
    }

    private void assertAgentExecutable(Agent agent, Long userId) {
        if (userId != null && userId.equals(agent.getCreatedBy())) {
            return;
        }
        if ("PUBLISHED".equalsIgnoreCase(agent.getStatus())) {
            if (agentPermissionService.canUse(agent.getId(), userId, false)) {
                return;
            }
        }
        throw new BusinessException(ErrorCode.FORBIDDEN, "Agent is not executable: " + agent.getId());
    }

    private ExecutionContext buildContext(RuntimeNodeCallbackRequest request) {
        ExecutionContext context = new ExecutionContext(null, "WORKFLOW", null, null);
        context.setExecutionId(parseLong(request.getExecutionId()));
        context.setUserId(request.getUserId());
        Map<String, Object> effectiveInput = effectiveInput(request);
        if (effectiveInput != null) {
            effectiveInput.forEach((key, value) ->
                    context.getVariableStore().set(key, value == null ? "" : String.valueOf(value)));
            String message = inputMessage(effectiveInput);
            if (message != null) {
                context.getVariableStore().set("input", message);
                context.addMessage("user", message);
            }
        }
        return context;
    }

    /**
     * 渲染检索查询模板 {@code {{上游别名.输出键}} } / {@code {{平铺键}} }。sidecar 的 callback_input 已把
     * 上游节点输出按「别名.输出键」与平铺输出键合并进 request.input，故直接基于 input 建 VariableStore
     * 即可（与 SKILL/AGENT_REF 的 effectiveInput 渲染一致）。无 {@code {{}} } 的纯文本原样返回；
     * 引用了不存在的变量时保留原 token（与 VariableStore.renderTemplate 语义一致）。
     */
    private String renderQuery(String template, Map<String, Object> input) {
        if (template == null || template.isBlank() || input == null || input.isEmpty()) {
            return template;
        }
        com.superprogrammer.engine.context.VariableStore variableStore =
                new com.superprogrammer.engine.context.VariableStore();
        input.forEach((key, value) ->
                variableStore.set(key, value == null ? "" : String.valueOf(value)));
        return variableStore.renderTemplate(template);
    }

    private String inputMessage(RuntimeNodeCallbackRequest request) {
        return inputMessage(request.getInput());
    }

    private String inputMessage(Map<String, Object> input) {
        if (input == null) {
            return "";
        }
        Object value = firstPresentInputValue(input, "input", "message", "prompt", "text");
        return value == null ? "" : String.valueOf(value);
    }

    private Object firstPresentInputValue(Map<String, Object> input, String... keys) {
        for (String key : keys) {
            if (input.containsKey(key) && input.get(key) != null) {
                return input.get(key);
            }
        }
        return null;
    }

    private Map<String, Object> callbackMetadata(RuntimeNodeCallbackRequest request) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (request.getTraceId() != null) {
            metadata.put("traceId", request.getTraceId());
        }
        metadata.put("nodeId", request.getNodeId());
        return metadata;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> promptOverride(RuntimeNodeCallbackRequest request) {
        if (request.getMetadata() == null || !(request.getMetadata().get("nodeConfig") instanceof Map<?, ?> rawConfig)) {
            return Map.of();
        }
        Map<String, Object> config = (Map<String, Object>) rawConfig;
        Map<String, Object> override = new LinkedHashMap<>();
        for (String key : List.of("systemPrompt", "promptTemplate", "model", "temperature", "outputKey")) {
            Object value = config.get(key);
            if (value != null && !(value instanceof String stringValue && stringValue.isBlank())) {
                override.put(key, value);
            }
        }
        return override;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> effectiveInput(RuntimeNodeCallbackRequest request) {
        Map<String, Object> input = new LinkedHashMap<>();
        if (request.getInput() != null) {
            input.putAll(request.getInput());
        }
        Map<String, Object> config = nodeConfig(request);
        Object mappingsValue = config.get("inputMappings");
        if (!(mappingsValue instanceof Map<?, ?> rawMappings)) {
            return input;
        }
        com.superprogrammer.engine.context.VariableStore variableStore = new com.superprogrammer.engine.context.VariableStore();
        input.forEach((key, value) -> variableStore.set(key, value == null ? "" : String.valueOf(value)));
        rawMappings.forEach((targetKey, template) -> {
            if (targetKey == null || template == null) {
                return;
            }
            String rendered = variableStore.renderTemplate(String.valueOf(template));
            input.put(String.valueOf(targetKey), rendered);
            variableStore.set(String.valueOf(targetKey), rendered);
        });
        return input;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> nodeConfig(RuntimeNodeCallbackRequest request) {
        if (request.getMetadata() == null || !(request.getMetadata().get("nodeConfig") instanceof Map<?, ?> rawConfig)) {
            return Map.of();
        }
        return (Map<String, Object>) rawConfig;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Long.parseLong(value);
    }

    /**
     * 安全审计 #1：由 executionId 反查 execution_logs.triggeredBy 作为可信 userId。
     * <p>execution_logs 由 Java 在工作流启动时写入（{@code startExecution(triggeredBy)}），可信；
     * 反查不到（executionId 缺失/非数字/无记录）→ 返回 null，由调用方拒绝。
     */
    private Long resolveTrustedUserId(RuntimeNodeCallbackRequest request) {
        Long executionId;
        try {
            executionId = parseLong(request.getExecutionId());
        } catch (NumberFormatException e) {
            return null;
        }
        if (executionId == null) {
            return null;
        }
        ExecutionLog executionLog = executionLogService.getExecutionLog(executionId);
        return executionLog == null ? null : executionLog.getTriggeredBy();
    }
}
