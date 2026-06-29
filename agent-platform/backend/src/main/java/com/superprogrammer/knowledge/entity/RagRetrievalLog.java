package com.superprogrammer.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * rag_retrieval_logs：检索可观测审计流，全量只追加（v6 §8.5）。
 * 表无 deleted/version/created_by/updated → 不继承 BaseEntity，自定义 @Insert 写入。
 */
@Data
@TableName("rag_retrieval_logs")
public class RagRetrievalLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String traceId;

    private Long tenantId;

    private Long userId;

    /** USER / SERVICE_ACCOUNT */
    private String identityType;

    private String kbIds;

    private String query;

    private String rewrittenQuery;

    /** ECONOMY / BALANCED / PRECISION（Phase1 默认 BALANCED）*/
    private String mode;

    /** JSON：候选 L0 */
    private String candidatesL0;

    /** JSON：候选 L1（Phase3 doc 级语义锚命中；CACHE_HIT/NO_VISIBLE_DOCS/ERROR 等短路前未算 → null） */
    private String candidatesL1;

    /** BM25-only 候选是否进入 pool（D1 兜底标记）*/
    private Boolean l2LexicalFallback;

    /** JSON：注入证据 L2 */
    private String evidenceL2;

    private String memoryHits;

    /** SUPPORTED / LOW_CONFIDENCE / NO_DENSE_HITS / NO_VISIBLE_DOCS / CITATION_CHECK_FAIL / ERROR */
    private String cragVerdict;

    /** JSON：token 预算 */
    private String tokenBudget;

    private Long latencyMs;

    private OffsetDateTime createdAt;
}
