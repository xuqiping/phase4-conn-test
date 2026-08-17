package com.superprogrammer.knowledge.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class RagRetrieveVO {

    private String traceId;
    private boolean abstained;
    /** abstain 时的话术/原因（SUPPORTED 时为 null）*/
    private String abstainReason;
    /** 灰区（hard≤sim<soft）回答但低置信；true=证据边缘、答案仅供参考。仅 SUPPORTED 类路径可能置 true。 */
    private boolean lowConfidence;
    /** SUPPORTED/PARTIAL/CONFLICT/INSUFFICIENT/OUT_OF_SCOPE/RETRIEVAL_FAILED. */
    private String confidenceState;
    private String answer;
    private List<CitationVO> citations;
    private List<RecallHitVO> candidatesL0;
    /** L1 文档向量召回命中（Phase3；空=短路路径未算 L1）。调试用，让 doc 级语义锚通道可见。 */
    private List<L1RecallHitVO> candidatesL1;
    /** 词法兜底是否触发（rag_retrieval_logs.l2_lexical_fallback 同源）：true=有纯 BM25（无向量父锚）候选进入 pool。 */
    private boolean bm25Fallback;
    /** 进入 rerank topK 的纯 BM25 候选（bm25Only=true，无 L0/L1 父锚，纯词法兜底命中）。空=bm25Fallback=false。 */
    private List<Bm25HitVO> candidatesBm25;
    private List<EvidenceVO> evidenceL2;
    private TokenBudgetVO tokenBudget;
    private long latencyMs;
    /** QueryPlan → RRF → Ranking 调试时间线；不含 Query/Chunk 正文。 */
    private List<RetrievalStageVO> retrievalTimeline;

    @Data
    @Builder
    public static class RetrievalStageVO {
        private String stage;
        private String configuredMode;
        private String effectiveMode;
        private String model;
        private int candidateCount;
        private long latencyMs;
        private String status;
    }

    @Data
    @Builder
    public static class CitationVO {
        private int index;
        private Long documentId;
        private String title;
        private Long nodeId;
        /** IMAGE/FILE 文档回显用：docType 决定渲染缩略图还是下载链；fileRef/mime/originalName 指向原件。 */
        private String docType;
        private String fileRef;
        private String mime;
        private String originalName;
        /** 联网搜索来源 URL（web citation）：非空 → 前端渲染为可点击外链（区别于 KB 的 documentId 引用）。
         *  编号空间与 KB 隔离：web citation 的 index 由注入层独立编排（KB 之后顺延，避免 [n] 撞号）。 */
        private String url;
        /** 联网搜索来源摘要（snippet），web citation 卡片副标题用。KB 引用为 null。 */
        private String snippet;
        /** 14x#3：引用来自保密库且请求者非 owner/admin → 前端隐藏缩略图/下载入口（asset 403 兜底）。
         *  答案缓存命中回放的旧条目无此标志= false，隐藏仅是 UX、强制在后端 /asset。 */
        private boolean confidential;
        private String page;
        private String article;
        private String sheet;
        private String cellRange;
        private String bbox;
    }

    @Data
    @Builder
    public static class RecallHitVO {
        private Long nodeId;
        private Long documentId;
        private String title;
        /** L0 摘要原文（node.content）。调试面板展示，让 Sheet:行N 占位 title 背后真实摘要可见。 */
        private String content;
        private double cosineDistance;
        private double cosineSimilarity;
    }

    /** L1 文档向量召回命中（doc 级语义锚，无 nodeId，按 documentId 去重）。 */
    @Data
    @Builder
    public static class L1RecallHitVO {
        private Long documentId;
        private String title;
        private double cosineDistance;
        private double cosineSimilarity;
        /** L1 元数据（向量化文本来源，调试展示用）：摘要。空=该文档无 l1_metadata。 */
        private String summary;
        /** 大纲各项"；"拼接。 */
        private String outline;
        /** 要点各项"；"拼接。 */
        private String importantRules;
    }

    /** 纯 BM25 词法兜底候选（无 L0/L1 父锚，仅词法命中）。bm25Rank 可空（未算/非词法来源）。 */
    @Data
    @Builder
    public static class Bm25HitVO {
        private Long nodeId;
        private Long documentId;
        private String title;
        private Double bm25Rank;
    }

    @Data
    @Builder
    public static class EvidenceVO {
        private Long nodeId;
        private Long documentId;
        private String title;
        private String content;
        private String contentHash;
        private String docType;
        /** IMAGE/FILE 回显用（docType=IMAGE 渲染缩略图，FILE 渲染下载链）。null=普通文本证据。 */
        private String fileRef;
        private String mime;
        private String originalName;
        private int citationIndex;
        private double rerankScore;
    }

    @Data
    @Builder
    public static class TokenBudgetVO {
        private int maxContextTokens;
        private int modelMaxContext;
        private int answerTokenReserve;
        private int effectiveContextCap;
        private int promptTokens;
    }
}
