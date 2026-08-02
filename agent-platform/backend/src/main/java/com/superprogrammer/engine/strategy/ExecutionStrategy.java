package com.superprogrammer.engine.strategy;

import com.superprogrammer.chat.dto.StreamEvent;
import com.superprogrammer.engine.context.ExecutionContext;
import reactor.core.publisher.Flux;

public interface ExecutionStrategy {
    String execute(ExecutionContext context, String userMessage);

    default Flux<StreamEvent> stream(ExecutionContext context, String userMessage) {
        return Flux.just(StreamEvent.chunk(execute(context, userMessage)), StreamEvent.done());
    }
}
