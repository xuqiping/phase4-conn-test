package com.superprogrammer.llm.provider;

import com.superprogrammer.chat.dto.StreamEvent;
import com.superprogrammer.llm.dto.LlmRequest;
import com.superprogrammer.llm.dto.LlmResponse;
import reactor.core.publisher.Flux;

public interface LlmProviderInterface {
    String getName();
    LlmResponse chat(LlmRequest request);
    Flux<StreamEvent> chatStream(LlmRequest request);
    boolean supports(String model);
}
