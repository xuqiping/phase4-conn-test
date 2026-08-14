package com.superprogrammer.llm.provider;

import com.superprogrammer.chat.dto.StreamEvent;
import com.superprogrammer.llm.dto.LlmRequest;
import com.superprogrammer.llm.dto.LlmResponse;
import com.superprogrammer.llm.dto.TokenUsage;
import com.superprogrammer.llm.dto.EmbedResult;
import com.superprogrammer.llm.dto.RerankRequest;
import com.superprogrammer.llm.dto.RerankResult;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.function.Consumer;

public interface LlmProviderInterface {
    String getName();
    LlmResponse chat(LlmRequest request);
    Flux<StreamEvent> chatStream(LlmRequest request);
    boolean supports(String model);

    /** Provider 明确声明的模型列表；空表表示通配/未声明。 */
    default List<String> getSupportedModels() {
        return List.of();
    }

    /**
     * 流式 usage side-channel（计费用）：解析流末 usage 写入 {@code usageSink}，
     * 供调用方（gateway 计费路径）在 {@code doOnComplete} 采+扣。<b>绝不改发出的 StreamEvent 流</b>。
     * <p>默认忽略 usage（直接回落老 {@link #chatStream(LlmRequest)}），13 个非计费调用方零回归；
     * OpenAI/Claude provider 覆写本方法解析 usage。
     *
     * @param usageSink usage 到达回调；可能从不触发（流异常 / provider 不回 usage 的末 chunk）。
     *                  调用方须自行判空与降级（估算兜底）。
     */
    default Flux<StreamEvent> chatStream(LlmRequest request, Consumer<TokenUsage> usageSink) {
        return chatStream(request);
    }

    /** 文本向量嵌入。Phase1 供 RAG dense 召回 + answer_cache ANN。返回 float[]，维度随模型。 */
    float[] embed(String text, String model);

    /**
     * embedding 调用 + usage（Step11 计费用）。默认实现忽略 usage（回落老 {@link #embed}），
     * usage=null——调用方（gateway）须自行估算兜底。OpenAI 兼容 provider 覆写解析 {@code /usage}。
     *
     * @return {@link EmbedResult}：向量 + usage（usage 可空）
     */
    default EmbedResult embedWithUsage(String text, String model) {
        return new EmbedResult(embed(text, model), null);
    }

    /** 专用重排能力；未实现的 Provider 必须失败关闭，禁止回落到 Chat。 */
    default RerankResult rerank(RerankRequest request) {
        throw new UnsupportedOperationException("Provider 不支持专用重排");
    }

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
