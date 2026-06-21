package com.superprogrammer.engine.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.engine.context.ExecutionContext;
import com.superprogrammer.llm.LlmGateway;
import com.superprogrammer.llm.dto.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LlmCallHandlerTest {

    @Mock
    private LlmGateway llmGateway;

    private LlmCallHandler handler;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        handler = new LlmCallHandler(llmGateway, objectMapper);
    }

    @Test
    void execute_shouldRenderTemplateAndCallLlm() {
        ExecutionContext ctx = new ExecutionContext(1L, "AGENT", 1L, null);
        ctx.getVariableStore().set("input", "Hello World");

        String stepConfig = "{\"promptTemplate\":\"分析以下内容：{{input}}\",\"model\":\"deepseek-chat\",\"outputKey\":\"analysis\"}";

        LlmResponse mockResp = LlmResponse.builder()
                .content("这是一个问候语")
                .usage(TokenUsage.builder().totalTokens(50).build())
                .duration(300L)
                .build();
        when(llmGateway.chat(any())).thenReturn(mockResp);

        StepResult result = handler.execute(stepConfig, ctx);

        assertTrue(result.isSuccess());
        assertEquals("这是一个问候语", result.getOutput());
        assertEquals("这是一个问候语", ctx.getVariableStore().get("analysis"));
        verify(llmGateway).chat(argThat(req ->
                req.getMessages().get(0).getContent().contains("Hello World")));
    }

    @Test
    void execute_withModelOverride_shouldUseSpecifiedModel() {
        ExecutionContext ctx = new ExecutionContext(1L, "AGENT", 1L, null);
        ctx.getVariableStore().set("input", "你好");

        String stepConfig = "{\"promptTemplate\":\"{{input}}\",\"model\":\"gpt-4\",\"outputKey\":\"result\"}";

        LlmResponse mockResp = LlmResponse.builder()
                .content("回复")
                .usage(TokenUsage.builder().totalTokens(10).build())
                .build();
        when(llmGateway.chat(any())).thenReturn(mockResp);

        handler.execute(stepConfig, ctx);

        verify(llmGateway).chat(argThat(req -> "gpt-4".equals(req.getModel())));
    }

    @Test
    void execute_withSystemPrompt_sendsSystemAndUserMessages() {
        ExecutionContext ctx = new ExecutionContext(1L, "AGENT", 1L, null);
        ctx.getVariableStore().set("input", "联调日志");

        String stepConfig = """
                {
                  "systemPrompt": "你是联调验收助手",
                  "promptTemplate": "总结：{{input}}",
                  "model": "doubao-seed-2.0-code",
                  "outputKey": "summary"
                }
                """;

        LlmResponse mockResp = LlmResponse.builder()
                .content("联调通过")
                .usage(TokenUsage.builder().totalTokens(10).build())
                .build();
        when(llmGateway.chat(any())).thenReturn(mockResp);

        handler.execute(stepConfig, ctx);

        verify(llmGateway).chat(argThat(req ->
                req.getMessages().size() == 2
                        && "system".equals(req.getMessages().get(0).getRole())
                        && "你是联调验收助手".equals(req.getMessages().get(0).getContent())
                        && "user".equals(req.getMessages().get(1).getRole())
                        && req.getMessages().get(1).getContent().contains("联调日志")));
    }
}
