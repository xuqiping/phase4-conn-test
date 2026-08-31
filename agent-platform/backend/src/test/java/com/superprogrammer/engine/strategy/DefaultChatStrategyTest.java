package com.superprogrammer.engine.strategy;

import com.superprogrammer.engine.context.ExecutionContext;
import com.superprogrammer.llm.LlmGateway;
import com.superprogrammer.llm.dto.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DefaultChatStrategyTest {

    @Mock
    private LlmGateway llmGateway;

    @InjectMocks
    private DefaultChatStrategy strategy;

    @Test
    void execute_shouldCallLlmAndReturnResponse() {
        LlmResponse mockResp = LlmResponse.builder()
                .content("你好！有什么可以帮助你的？")
                .model("selected-chat-model")
                .duration(500L)
                .usage(TokenUsage.builder().promptTokens(10).completionTokens(20).totalTokens(30).build())
                .build();
        when(llmGateway.chat(any(), any())).thenReturn(mockResp);

        ExecutionContext ctx = new ExecutionContext(1L, "CHAT", null, null);
        ctx.setModel("selected-chat-model");
        ctx.addMessage("user", "你好");

        String result = strategy.execute(ctx, "你好");

        assertEquals("你好！有什么可以帮助你的？", result);
        verify(llmGateway).chat(argThat(req ->
                req.getModel().equals("selected-chat-model") &&
                req.getMessages().stream().anyMatch(m -> "user".equals(m.getRole()) && "你好".equals(m.getContent()))
        ), any());
    }

    /** 修复IX-1 A1：思考档位 ExecutionContext→LlmRequest 透传（三档值）。 */
    @Test
    void execute_shouldPassThinkingLevelThrough() {
        when(llmGateway.chat(any(), any())).thenReturn(LlmResponse.builder()
                .content("ok").model("m")
                .usage(TokenUsage.builder().promptTokens(1).completionTokens(1).totalTokens(2).build())
                .build());

        ExecutionContext ctx = new ExecutionContext(1L, "CHAT", null, null);
        ctx.setModel("m");
        ctx.setThinkingLevel(ThinkingLevel.STANDARD);
        ctx.addMessage("user", "hi");

        strategy.execute(ctx, "hi");

        verify(llmGateway).chat(argThat(req -> ThinkingLevel.STANDARD == req.getThinkingLevel()), any());
    }

    /** 修复IX-1 A1：未选档（null）透传 null——锁「不发思考参数」现状基线（坑1）。 */
    @Test
    void execute_shouldKeepNullThinkingLevelAsNull() {
        when(llmGateway.chat(any(), any())).thenReturn(LlmResponse.builder()
                .content("ok").model("m")
                .usage(TokenUsage.builder().promptTokens(1).completionTokens(1).totalTokens(2).build())
                .build());

        ExecutionContext ctx = new ExecutionContext(1L, "CHAT", null, null);
        ctx.addMessage("user", "hi");

        strategy.execute(ctx, "hi");

        verify(llmGateway).chat(argThat(req -> req.getThinkingLevel() == null), any());
    }
}
