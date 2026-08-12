package com.superprogrammer.engine.strategy;

import com.superprogrammer.chat.dto.StreamEvent;
import com.superprogrammer.engine.context.ExecutionContext;
import com.superprogrammer.llm.LlmGateway;
import com.superprogrammer.llm.dto.LlmRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultChatStrategy implements ExecutionStrategy {

    private final LlmGateway llmGateway;
    private String resolveModel(ExecutionContext context) {
        return context.getModel();
    }

    @Override
    public String execute(ExecutionContext context, String userMessage) {
        log.info("默认聊天模式执行, session={}", context.getSessionId());

        context.addMessage("user", userMessage);

        LlmRequest request = LlmRequest.builder()
                .model(resolveModel(context))
                .messages(context.getMessageHistory())
                .build();

        var response = llmGateway.chat(request, context.getUserId());
        context.addMessage("assistant", response.getContent());

        log.info("LLM回复完成, tokens={}, duration={}ms",
                response.getUsage().getTotalTokens(), response.getDuration());
        return response.getContent();
    }

    @Override
    public Flux<StreamEvent> stream(ExecutionContext context, String userMessage) {
        log.info("默认聊天模式流式执行, session={}", context.getSessionId());

        context.addMessage("user", userMessage);

        LlmRequest request = LlmRequest.builder()
                .model(resolveModel(context))
                .messages(context.getMessageHistory())
                .stream(true)
                .build();

        return llmGateway.chatStream(request, context.getUserId());
    }
}
