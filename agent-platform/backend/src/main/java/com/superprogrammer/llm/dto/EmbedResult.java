package com.superprogrammer.llm.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * embedding 调用结果 + usage（计费用，Step11）。
 * <p>{@link #embedding} 向量（维度随模型，RAG 用 halfvec(2048)）；
 * {@link #usage} 可空——provider 未回 usage（如老端点 / 估算兜底）时为 null，
 * 调用方（gateway）降级用 {@code TokenEstimator.estimate} 估 input、output=0、status=ESTIMATED。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmbedResult {
    private float[] embedding;
    /** 可空：provider 未回 usage 时为 null。 */
    private TokenUsage usage;
}
