package com.superprogrammer.engine;

import com.superprogrammer.engine.context.ExecutionContext;
import com.superprogrammer.chat.dto.StreamEvent;
import com.superprogrammer.engine.strategy.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrchestrationEngine {

    private final DefaultChatStrategy defaultChatStrategy;
    private final AgentRoutingStrategy agentRoutingStrategy;
    private final WorkflowStrategy workflowStrategy;

    public String execute(ExecutionContext context, String userMessage) {
        String mode = context.getMode();
        log.info("OrchestrationEngine执行, mode={}, session={}", mode, context.getSessionId());

        return switch (mode) {
            case "CHAT" -> defaultChatStrategy.execute(context, userMessage);
            case "AGENT" -> agentRoutingStrategy.execute(context, userMessage);
            case "WORKFLOW" -> workflowStrategy.execute(context, userMessage);
            default -> {
                log.warn("未知执行模式: {}", mode);
                yield "不支持的执行模式: " + mode;
            }
        };
    }

    public Flux<StreamEvent> executeStream(ExecutionContext context, String userMessage) {
        String mode = context.getMode();
        log.info("OrchestrationEngine流式执行, mode={}, session={}", mode, context.getSessionId());

        return switch (mode) {
            case "CHAT" -> defaultChatStrategy.stream(context, userMessage);
            case "AGENT" -> agentRoutingStrategy.stream(context, userMessage);
            case "WORKFLOW" -> workflowStrategy.stream(context, userMessage);
            default -> {
                log.warn("未知执行模式: {}", mode);
                yield Flux.just(StreamEvent.chunk("不支持的执行模式: " + mode), StreamEvent.done());
            }
        };
    }
}
