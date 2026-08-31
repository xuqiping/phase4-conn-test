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
                // 17x-2026-08-25：会话归户——usage 落 session_id（组产出「查看结果」/SUM 封顶用）
                .sessionId(context.getSessionId() == null ? null : String.valueOf(context.getSessionId()))
                // 计划5 Step4：组池计费透传（null=个人）
                .projectGroupId(context.getProjectGroupId())
                // 修复IX-1：思考强度档位透传（null=不发思考参数，现状）
                .thinkingLevel(context.getThinkingLevel())
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
                // 17x-2026-08-25：会话归户——usage 落 session_id（组产出「查看结果」/SUM 封顶用）
                .sessionId(context.getSessionId() == null ? null : String.valueOf(context.getSessionId()))
                // 计划5 Step4：组池计费透传（null=个人）
                .projectGroupId(context.getProjectGroupId())
                // 修复IX-1：思考强度档位透传（null=不发思考参数，现状）
                .thinkingLevel(context.getThinkingLevel())
                .build();

        return llmGateway.chatStream(request, context.getUserId());
    }
}
