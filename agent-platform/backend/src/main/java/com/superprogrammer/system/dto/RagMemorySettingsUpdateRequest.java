package com.superprogrammer.system.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** RAG/记忆模式全局开关更新（V26）。 */
@Data
public class RagMemorySettingsUpdateRequest {
    @NotNull
    private Boolean enabled;
    /** 可选：ASYNC / HYBRID（非法或缺省 → ASYNC）。 */
    private String processMode;
    /** 可选：LLM_FULL_CONTEXT=全量(默认) / EMBEDDING_VECTOR=向量 top-K / VECTOR_KEYWORD=向量+关键词hybrid+LLM兜底（非法或缺省 → LLM_FULL_CONTEXT）。 */
    private String retrievalMode;
    /** 可选：EN(默认) / ZH（非法或缺省 → EN）。控制注入上下文用英文 key 还是中文 key_zh 展示。 */
    private String keyLanguage;
    /** 可选：全量模式记忆阈值，0=禁用始终全量（缺省 → 不改）。仅 LLM_FULL_CONTEXT 生效。 */
    private Integer fullContextThreshold;
    /** 可选：关键词召回 per-block_label 阈值，0=禁用不分组筛（缺省 → 不改）。仅 VECTOR_KEYWORD 生效。 */
    private Integer keywordPerBlockThreshold;
    /** 可选：LLM_KEY 粗筛 top-N（<1=默认40；缺省 → 不改）。仅 LLM_KEY 生效。 */
    private Integer llmKeyCoarseTopN;
    /** 可选：LLM_KEY 精排开关（缺省 → 不改）。仅 LLM_KEY 生效。 */
    private Boolean llmKeyRerank;
    /** 可选：关键词召回分词上限（0=不限；缺省 → 不改）。 */
    private Integer keywordMax;
}
