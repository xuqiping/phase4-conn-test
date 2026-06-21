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

    /** 文本向量嵌入。Phase1 供 RAG dense 召回 + answer_cache ANN。返回 float[]，维度随模型。 */
    float[] embed(String text, String model);
}
