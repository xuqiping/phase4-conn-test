package com.superprogrammer.runtime.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.execution.entity.ExecutionLog;
import com.superprogrammer.execution.service.ExecutionLogService;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.runtime.config.RuntimeGatewayProperties;
import com.superprogrammer.runtime.dto.ExecutionEvent;
import com.superprogrammer.runtime.dto.ExecutionRequest;
import com.superprogrammer.runtime.dto.WorkflowDefinition;
import com.superprogrammer.workflow.dto.WorkflowDetailVO;
import com.superprogrammer.workflow.service.WorkflowService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RuntimeExecutionService {

    private final WorkflowService workflowService;
    private final WorkflowDefinitionAssembler workflowDefinitionAssembler;
    private final RuntimeGateway runtimeGateway;
    private final ExecutionLogService executionLogService;
    private final ObjectMapper objectMapper;
    private final RuntimeGatewayProperties runtimeGatewayProperties;

    public Flux<ExecutionEvent> runWorkflow(Long workflowId, Long userId, Map<String, Object> input) {
        WorkflowDetailVO workflow = workflowService.getWorkflowDetail(workflowId);
        WorkflowDefinition definition = workflowDefinitionAssembler.assemble(workflow);
        return runWorkflowDefinition(workflow, definition, userId, null, input, Map.of());
    }

    public Flux<ExecutionEvent> runWorkflowFromChat(Long workflowId, Long userId, Long sessionId, String message) {
        WorkflowDetailVO workflow = workflowService.getWorkflowDetail(workflowId);
        WorkflowDefinition definition = workflowDefinitionAssembler.assemble(workflow);
        return runWorkflowDefinition(workflow, definition, userId, sessionId, chatInput(workflow, message), Map.of());
    }

    public Flux<ExecutionEvent> retryWorkflowExecution(Long executionId, Long userId) {
        ExecutionLog previous = executionLogService.getExecutionLog(executionId);
        if (previous.getWorkflowId() == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "原执行记录缺少workflowId，无法重试");
        }
        WorkflowDetailVO workflow = workflowService.getWorkflowDetail(previous.getWorkflowId());
        WorkflowDefinition definition = workflowDefinitionAssembler.assemble(workflow);
        return runWorkflowDefinition(
                workflow,
                definition,
                userId,
                previous.getSessionId(),
                Map.of(),
                Map.of("retryOfExecutionId", String.valueOf(executionId)));
    }

    public Flux<ExecutionEvent> resumeWorkflowFromCheckpoint(String checkpointRef, Long userId) {
        ExecutionLog previous = executionLogService.findByCheckpointRef(checkpointRef);
        if (previous.getWorkflowId() == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "checkpoint执行记录缺少workflowId，无法恢复");
        }
        WorkflowDetailVO workflow = workflowService.getWorkflowDetail(previous.getWorkflowId());
        WorkflowDefinition definition = workflowDefinitionAssembler.assemble(workflow);
        return runWorkflowDefinition(
                workflow,
                definition,
                userId,
                previous.getSessionId(),
                Map.of(),
                Map.of(
                        "resumeFromCheckpointRef", checkpointRef,
                        "resumeOfExecutionId", String.valueOf(previous.getId())));
    }

    public Flux<ExecutionEvent> approveWorkflowExecution(Long executionId, Long userId) {
        ExecutionLog previous = executionLogService.getExecutionLog(executionId);
        if (previous.getWorkflowId() == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "审批执行记录缺少workflowId，无法继续");
        }
        if (previous.getCheckpointRef() == null || previous.getCheckpointRef().isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "审批执行记录缺少checkpointRef，无法继续");
        }
        WorkflowDetailVO workflow = workflowService.getWorkflowDetail(previous.getWorkflowId());
        WorkflowDefinition definition = workflowDefinitionAssembler.assemble(workflow);
        return runWorkflowDefinition(
                workflow,
                definition,
                userId,
                previous.getSessionId(),
                Map.of(),
                Map.of(
                        "resumeFromCheckpointRef", previous.getCheckpointRef(),
                        "approvalDecision", "approved",
                        "approvalOfExecutionId", String.valueOf(previous.getId())));
    }

    /**
     * 用户对 HUMAN_INPUT 节点作答后恢复执行：携带 userInput 续跑。
     * 原 WAITING_INPUT 执行标记为 RESUMED，新建恢复执行（沿用同一 session 以便后续拦截）。
     */
    public Flux<ExecutionEvent> resumeWorkflowWithInput(Long executionId, Map<String, Object> userInput, Long userId) {
        ExecutionLog previous = executionLogService.getExecutionLog(executionId);
        if (previous.getWorkflowId() == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "输入恢复执行记录缺少workflowId，无法继续");
        }
        if (previous.getCheckpointRef() == null || previous.getCheckpointRef().isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "输入恢复执行记录缺少checkpointRef，无法继续");
        }
        if (!"WAITING_INPUT".equals(previous.getStatus())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "该执行不在等待用户输入状态");
        }
        WorkflowDetailVO workflow = workflowService.getWorkflowDetail(previous.getWorkflowId());
        WorkflowDefinition definition = workflowDefinitionAssembler.assemble(workflow);
        executionLogService.markInputResumed(executionId);
        Map<String, Object> runtimeOverrides = new LinkedHashMap<>();
        runtimeOverrides.put("resumeFromCheckpointRef", previous.getCheckpointRef());
        runtimeOverrides.put("userInput", userInput);
        runtimeOverrides.put("inputResumeOfExecutionId", String.valueOf(previous.getId()));
        return runWorkflowDefinition(workflow, definition, userId, previous.getSessionId(), Map.of(), runtimeOverrides);
    }

    /**
     * 对话流入口：若该会话有 WAITING_INPUT 挂起，把用户消息当答案恢复执行；否则返回 null（走正常工作流分发）。
     */
    public Flux<ExecutionEvent> resumeWorkflowFromChatAnswer(Long sessionId, String answer, Long userId) {
        if (sessionId == null || answer == null) {
            return null;
        }
        ExecutionLog pending = executionLogService.findPendingInputBySession(sessionId);
        if (pending == null) {
            return null;
        }
        Map<String, Object> spec = executionLogService.readPendingInput(pending);
        if (spec == null || spec.get("inputKey") == null) {
            return null;
        }
        Map<String, Object> userInput = new LinkedHashMap<>();
        userInput.put(String.valueOf(spec.get("inputKey")), answer);
        return resumeWorkflowWithInput(pending.getId(), userInput, userId);
    }


    private Flux<ExecutionEvent> runWorkflowDefinition(
            WorkflowDetailVO workflow,
            WorkflowDefinition definition,
            Long userId,
            Long sessionId,
            Map<String, Object> input,
            Map<String, Object> runtimeOverrides) {
        String traceId = "trace-" + UUID.randomUUID();

        ExecutionLog executionLog = executionLogService.startRuntimeExecution(
                workflow.getId(),
                workflow.getName(),
                userId,
                "WORKFLOW",
                workflow.getId(),
                sessionId,
                null,
                null,
                traceId);

        Map<String, Object> runtime = new LinkedHashMap<>();
        runtime.put("stream", true);
        runtime.put("checkpoint", true);
        runtime.put("maxDepth", 8);
        runtime.put("traceId", traceId);
        runtime.put("javaCallbackBaseUrl", runtimeGatewayProperties.getJavaCallbackBaseUrl());
        runtime.putAll(runtimeOverrides);

        ExecutionRequest request = ExecutionRequest.builder()
                .executionId(String.valueOf(executionLog.getId()))
                .rootExecutionId(String.valueOf(executionLog.getRootExecutionId()))
                .userId(userId)
                .sourceType("WORKFLOW")
                .sourceId(workflow.getId())
                .workflow(definition)
                .input(input)
                .runtime(runtime)
                .build();

        return runtimeGateway.run(request)
                .doOnNext(event -> persistEvent(executionLog.getId(), event))
                .doOnError(error -> executionLogService.failExecution(executionLog.getId(), error.getMessage()));
    }

    private void persistEvent(Long executionId, ExecutionEvent event) {
        executionLogService.appendRuntimeEventSnapshot(executionId, eventSnapshot(event));
        updateRuntimeRefs(executionId, event);
        if ("EXECUTION_COMPLETED".equals(event.getType())) {
            executionLogService.finishExecution(executionId, null);
        } else if ("EXECUTION_FAILED".equals(event.getType())) {
            executionLogService.failExecution(executionId, failureMessage(event));
        } else if ("WAITING_APPROVAL".equals(event.getType())) {
            executionLogService.waitForApproval(executionId, event.getNodeId(), approvalKey(event));
        } else if ("WAITING_INPUT".equals(event.getType())) {
            executionLogService.waitForInput(executionId, buildPendingInput(event));
        }
    }

    private Map<String, Object> buildPendingInput(ExecutionEvent event) {
        Map<String, Object> metadata = event.getMetadata() == null ? Map.of() : event.getMetadata();
        Map<String, Object> pending = new LinkedHashMap<>();
        pending.put("nodeId", event.getNodeId());
        pending.put("inputKey", metadata.get("inputKey"));
        pending.put("question", metadata.get("question"));
        pending.put("inputType", metadata.getOrDefault("inputType", "text"));
        pending.put("options", metadata.get("options"));
        pending.put("required", metadata.getOrDefault("required", Boolean.TRUE));
        pending.put("placeholder", metadata.get("placeholder"));
        pending.put("checkpointRef", metadata.get("inputCheckpointRef"));
        return pending;
    }

    private Map<String, Object> eventSnapshot(ExecutionEvent event) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("executionId", event.getExecutionId());
        snapshot.put("rootExecutionId", event.getRootExecutionId());
        snapshot.put("parentExecutionId", event.getParentExecutionId());
        snapshot.put("nodeId", event.getNodeId());
        snapshot.put("type", event.getType());
        snapshot.put("status", event.getStatus());
        snapshot.put("sourceType", event.getSourceType());
        snapshot.put("sourceId", event.getSourceId());
        snapshot.put("input", event.getInput());
        snapshot.put("output", event.getOutput());
        snapshot.put("metadata", event.getMetadata());
        snapshot.put("timestamp", event.getTimestamp());
        return snapshot;
    }

    private void updateRuntimeRefs(Long executionId, ExecutionEvent event) {
        if (event.getMetadata() == null) {
            return;
        }
        Object externalThreadId = event.getMetadata().get("externalThreadId");
        Object checkpointRef = event.getMetadata().get("checkpointRef");
        if (checkpointRef == null && "EXECUTION_FAILED".equals(event.getType())) {
            checkpointRef = event.getMetadata().get("recoveryCheckpointRef");
        }
        if (checkpointRef == null && "WAITING_APPROVAL".equals(event.getType())) {
            checkpointRef = event.getMetadata().get("approvalCheckpointRef");
        }
        if (checkpointRef == null && "WAITING_INPUT".equals(event.getType())) {
            checkpointRef = event.getMetadata().get("inputCheckpointRef");
        }
        if (externalThreadId != null || checkpointRef != null) {
            executionLogService.updateRuntimeRefs(
                    executionId,
                    externalThreadId == null ? null : String.valueOf(externalThreadId),
                    checkpointRef == null ? null : String.valueOf(checkpointRef));
        }
    }

    private String failureMessage(ExecutionEvent event) {
        if (event.getMetadata() == null) {
            return event.getStatus();
        }
        Object failedNodeId = event.getMetadata().get("failedNodeId");
        Object errorMessage = event.getMetadata().get("errorMessage");
        if (failedNodeId != null && errorMessage != null) {
            return "节点 " + failedNodeId + " 执行失败: " + errorMessage;
        }
        if (errorMessage != null) {
            return String.valueOf(errorMessage);
        }
        return event.getStatus();
    }

    private String approvalKey(ExecutionEvent event) {
        if (event.getMetadata() == null || event.getMetadata().get("approvalKey") == null) {
            return event.getNodeId();
        }
        return String.valueOf(event.getMetadata().get("approvalKey"));
    }

    private Map<String, Object> chatInput(WorkflowDetailVO workflow, String message) {
        Map<String, Object> input = new LinkedHashMap<>();
        String safeMessage = message == null ? "" : message;
        input.put("input", safeMessage);
        input.put("message", safeMessage);
        input.put("prompt", safeMessage);
        input.put("text", safeMessage);
        String startInputKey = startInputKey(workflow);
        if (startInputKey != null && !startInputKey.isBlank()) {
            input.put(startInputKey, safeMessage);
        }
        return input;
    }

    private String startInputKey(WorkflowDetailVO workflow) {
        if (workflow.getNodes() == null) {
            return null;
        }
        return workflow.getNodes().stream()
                .filter(node -> "START".equalsIgnoreCase(node.getType()))
                .map(node -> readConfigValue(node.getConfig(), "inputKey"))
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse(null);
    }

    private String readConfigValue(String configJson, String key) {
        if (configJson == null || configJson.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> config = objectMapper.readValue(configJson, Map.class);
            Object value = config.get(key);
            return value == null ? null : String.valueOf(value);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "工作流开始节点配置不是合法 JSON");
        }
    }

}
