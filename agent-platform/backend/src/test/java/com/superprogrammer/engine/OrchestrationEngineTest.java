package com.superprogrammer.engine;

import com.superprogrammer.engine.context.ExecutionContext;
import com.superprogrammer.engine.strategy.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrchestrationEngineTest {

    @Mock
    private DefaultChatStrategy defaultChatStrategy;

    @Mock
    private AgentRoutingStrategy agentRoutingStrategy;

    @Mock
    private WorkflowStrategy workflowStrategy;

    private OrchestrationEngine engine;

    @BeforeEach
    void setUp() {
        engine = new OrchestrationEngine(defaultChatStrategy, agentRoutingStrategy, workflowStrategy);
    }

    @Test
    void execute_chatMode_shouldUseDefaultChatStrategy() {
        when(defaultChatStrategy.execute(any(), eq("你好"))).thenReturn("你好！");

        ExecutionContext ctx = new ExecutionContext(1L, "CHAT", null, null);
        String result = engine.execute(ctx, "你好");

        assertEquals("你好！", result);
        verify(defaultChatStrategy).execute(any(), eq("你好"));
        verify(agentRoutingStrategy, never()).execute(any(), any());
    }

    @Test
    void execute_agentMode_shouldUseAgentRoutingStrategy() {
        when(agentRoutingStrategy.execute(any(), eq("写代码"))).thenReturn("代码已生成");

        ExecutionContext ctx = new ExecutionContext(1L, "AGENT", 1L, null);
        String result = engine.execute(ctx, "写代码");

        assertEquals("代码已生成", result);
        verify(agentRoutingStrategy).execute(any(), eq("写代码"));
    }

    @Test
    void execute_workflowMode_shouldUseWorkflowStrategy() {
        when(workflowStrategy.execute(any(), eq("运行"))).thenReturn("工作流完成");

        ExecutionContext ctx = new ExecutionContext(1L, "WORKFLOW", null, 1L);
        String result = engine.execute(ctx, "运行");

        assertEquals("工作流完成", result);
        verify(workflowStrategy).execute(any(), eq("运行"));
    }
}
