package com.superprogrammer.system.dto;

import lombok.Builder;
import lombok.Data;

/** RAG/记忆模式全局开关视图（V26）。 */
@Data
@Builder
public class RagMemorySettingsVO {
    private Boolean enabled;
    /** 记忆处理模式：ASYNC=全异步(不卡顿,冲突走面板) / HYBRID=同步(即时冲突追问 askText)。 */
    private String processMode;
    /** 记忆检索模式：LLM_FULL_CONTEXT=全量(默认) / EMBEDDING_VECTOR=向量 top-K / VECTOR_KEYWORD=向量+关键词hybrid+LLM兜底。 */
    private String retrievalMode;
    /** 记忆标签语言：EN=英文 key(默认) / ZH=中文 key_zh(空回退英文)。控制注入上下文用哪个 key 展示。 */
    private String keyLanguage;
    /** 全量模式记忆阈值（>此值改两阶段LLM筛key；0=禁用始终全量，默认20）。仅 LLM_FULL_CONTEXT 生效。 */
    private Integer fullContextThreshold;
    /** 关键词召回 per-block_label 阈值（同 block 命中>此值优先留高优 entities/key/key_zh；0=禁用，默认10）。仅 VECTOR_KEYWORD 生效。 */
    private Integer keywordPerBlockThreshold;
    /** LLM_KEY 粗筛 top-N（向量+BM25 RRF 融合后保留候选记忆数；<1=默认40）。仅 LLM_KEY 生效。 */
    private Integer llmKeyCoarseTopN;
    /** LLM_KEY 精排开关（true=粗筛后 LLM 双维度筛；false=直接注 top-N；默认 true）。仅 LLM_KEY 生效。 */
    private Boolean llmKeyRerank;
    /** 关键词召回分词上限（0=不限，避免 SQL OR 列表过长；默认8）。VECTOR_KEYWORD 关键词通道用。 */
    private Integer keywordMax;
    /** M3 entities 词袋计数配置（totalMax/variantMin-Max/properNounMin-Max/hypernymMin-Max）。LLM_KEY/VECTOR_KEYWORD 用。 */
    private MemoryEntitiesConfig entitiesConfig;
}
