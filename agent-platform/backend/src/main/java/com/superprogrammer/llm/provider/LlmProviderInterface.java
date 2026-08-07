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

    /**
     * 计费用：provider 主键 id。
     * <p>全局 provider = {@code llm_providers.id}；用户级 override = {@code user_llm_providers.id}
     * （独立 id 命名空间，靠 {@link #getProviderScope()} 区分）。配合 {@link #getProviderScope()} 写 llm_usage_logs。
     * 默认 null（历史/测试构造，无 id 上下文）。
     */
    default Long getId() {
        return null;
    }

    /**
     * 计费用：provider 归属域。{@code GLOBAL}=全局行，{@code USER}=用户自配 override。
     * 默认 GLOBAL（全局 provider 即默认域）。
     */
    default String getProviderScope() {
        return "GLOBAL";
    }
}
