package com.superprogrammer.chat.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/** 记忆注入预览（调试用）：展示三个检索设置的实际效果 + 最终注入 LLM 的上下文。 */
@Data
@Builder
public class MemoryContextPreviewVO {
    /** 当前检索模式：LLM_FULL_CONTEXT / EMBEDDING_VECTOR / VECTOR_KEYWORD / LLM_KEY。 */
    private String mode;
    /** 标签语言：EN / ZH / BOTH。 */
    private String keyLanguage;
    /** 全量阈值（仅 LLM_FULL_CONTEXT 生效）。 */
    private Integer threshold;
    /** 用户 confidence≥0.5 记忆总数。 */
    private Long totalMemories;
    /** 是否走了"超阈值两阶段"（先 key→LLM 选→再装 value）。 */
    private Boolean twoStage;
    /** 实际注入 LLM 的记忆上下文文本；null=不注入（无命中/LLM 判无关）。 */
    private String context;

    // ============================ V38 召回过程透出（LLM_KEY/两阶段调试）============================

    /** 粗筛 top-N 候选（LLM_KEY/VECTOR_KEYWORD 才有，按 RRF 分降序）。 */
    private List<CandidateVO> candidates;
    /** LLM 精排选中 memory_key 列表（LLM_KEY rerank / 全量超阈值两阶段 才有；否则 null）。 */
    private List<String> selectedKeys;
    /** 各召回通道命中计数（向量/关键词/BM25/LLM 兜底）。 */
    private ChannelHitVO channels;

    /** 粗筛候选行：key_zh + value 预览 + 所属块 + scope + 命中通道。 */
    @Data
    @Builder
    public static class CandidateVO {
        /** memory_key_zh（空→null，前端回退英文 key）。 */
        private String memoryKeyZh;
        /** memory_key 英文（前端 key_zh 空时兜底展示）。 */
        private String memoryKey;
        /** memory_value 预览（截断 60 字）。 */
        private String valuePreview;
        /** block_label（null→""）。 */
        private String blockLabel;
        /** scope：global / project（is_global）。 */
        private String scope;
        /** 命中通道：vector / bm25 / both / keyword。 */
        private String channel;
    }

    /** 召回通道命中统计。null=该模式无此通道；数值=命中条数。 */
    @Data
    @Builder
    public static class ChannelHitVO {
        /** 向量（anchor/EMBEDDING_VECTOR）命中数。 */
        private Integer vector;
        /** 关键词（VECTOR_KEYWORD entities 列）命中数。 */
        private Integer keyword;
        /** BM25（anchor_tokens_tsv）命中数。 */
        private Integer bm25;
        /** 是否触发 LLM-key 兜底（VECTOR_KEYWORD 0 命中救场）。 */
        private Boolean llmFallback;
    }
}
