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
                .model("deepseek-chat")
                .duration(500L)
                .usage(TokenUsage.builder().promptTokens(10).completionTokens(20).totalTokens(30).build())
                .build();
        when(llmGateway.chat(any(), any())).thenReturn(mockResp);

        ExecutionContext ctx = new ExecutionContext(1L, "CHAT", null, null);
        ctx.addMessage("user", "你好");

        String result = strategy.execute(ctx, "你好");

        assertEquals("你好！有什么可以帮助你的？", result);
        verify(llmGateway).chat(argThat(req ->
                req.getModel().equals("deepseek-chat") &&
                req.getMessages().stream().anyMatch(m -> "user".equals(m.getRole()) && "你好".equals(m.getContent()))
        ), any());
    }
}
