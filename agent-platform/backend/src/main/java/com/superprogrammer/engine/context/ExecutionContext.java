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
    /** 记忆模式开关（RagModeResolver 解析结果），供 AgentRoutingStrategy 等策略读取门控 RAG/记忆。 */
    private boolean ragEnabled;

    /** 项目组归属（计划5 Step4）：send 请求透传；策略构建 LlmRequest 时复制→组池计费。null=个人。 */
    private Long projectGroupId;

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

    public void setRagEnabled(boolean ragEnabled) {
        this.ragEnabled = ragEnabled;
    }

    public void setProjectGroupId(Long projectGroupId) {
        this.projectGroupId = projectGroupId;
    }

    public void addMessage(String role, String content) {
        messageHistory.add(LlmMessage.builder().role(role).content(content).build());
    }
}
