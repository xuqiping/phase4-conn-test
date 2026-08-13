package com.superprogrammer.knowledge.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * RAG 答案缓存参数（v6 §8.9a，阶段4-B）。
 * application.yml: rag.answer-cache.{enabled,sim-threshold,top-n,ttl-days}。
 *
 * <p>opt-in 默认关（同 {@code rag.memory.enabled} 哲学）：关 → {@link RagRetrievalService}
 * step2 直接跳过，行为同前（每次检索重跑 embed+dense+rerank）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "rag.answer-cache")
public class AnswerCacheProperties {

    /** 总开关（关 → 不查不写缓存，retrieve/retrieveEvidence 行为不变）。 */
    private boolean enabled = false;

    /** 近义命中门槛（cosine sim ≥ 此值才接受，保守防假命中返错答案；doubao abs sim 偏低，可调）。 */
    private double simThreshold = 0.90;

    /** HNSW 取近邻候选数（取 top-N 后逐个 P2/P3 验，首个通过即命中）。 */
    private int topN = 5;

    /** 缓存 TTL（天），写 decay_at = now + ttl，供阶段7 ReconciliationJob 清理 stale 行。 */
    private int ttlDays = 7;

    /** 检索编排协议版本；发布改变检索语义的代码时递增。 */
    private String pipelineVersion = "rag-pipeline-v1";

    /** 缓存载荷 Prompt 协议版本；修改生成/证据 Prompt 时递增。 */
    private String promptVersion = "rag-prompt-v1";
}
