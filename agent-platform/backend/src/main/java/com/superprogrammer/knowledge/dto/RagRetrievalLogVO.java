package com.superprogrammer.knowledge.dto;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * rag_retrieval_logs 审计视图（管理员检索可观测）。
 * 大 JSON 字段（candidatesL0/evidenceL2/tokenBudget）原样透传，前端按需解析。
 */
@Data
public class RagRetrievalLogVO {

    private Long id;
    private String traceId;
    private Long userId;
    private String identityType;
    private String kbIds;
    private String query;
    private String mode;

    /** BM25-only 候选是否进入 pool（D1 兜底标记） */
    private Boolean l2LexicalFallback;

    /** SUPPORTED / LOW_CONFIDENCE / NO_DENSE_HITS / NO_VISIBLE_DOCS / CITATION_CHECK_FAIL / ERROR */
    private String cragVerdict;

    private Long latencyMs;
    private OffsetDateTime createdAt;

    /** JSON：候选 L0（可选透传，体量大） */
    private String candidatesL0;
    /** JSON：注入证据 L2（可选透传，体量大） */
    private String evidenceL2;
    /** JSON：token 预算 */
    private String tokenBudget;
}
