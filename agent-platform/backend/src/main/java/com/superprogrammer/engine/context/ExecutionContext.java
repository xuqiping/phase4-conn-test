package com.superprogrammer.engine.context;

import com.superprogrammer.llm.dto.LlmMessage;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;

@Getter
public class ExecutionContext {

    private final Long sessionId;
    private Long executionId;
    private final String mode;
    private final Long agentId;
    private final Long workflowId;
    private final VariableStore variableStore;
    private final List<LlmMessage> messageHistory;
    private String model;
    private Long userId;

    public ExecutionContext(Long sessionId, String mode, Long agentId, Long workflowId) {
        this.sessionId = sessionId;
        this.mode = mode;
        this.agentId = agentId;
        this.workflowId = workflowId;
        this.variableStore = new VariableStore();
        this.messageHistory = new ArrayList<>();
    }

    public void setExecutionId(Long executionId) {
        this.executionId = executionId;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public void addMessage(String role, String content) {
        messageHistory.add(LlmMessage.builder().role(role).content(content).build());
    }
}
