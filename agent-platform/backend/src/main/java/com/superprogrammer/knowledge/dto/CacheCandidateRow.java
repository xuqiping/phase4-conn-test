package com.superprogrammer.knowledge.dto;

import lombok.Data;

/**
 * {@code rag_answer_cache} HNSW 近邻检索结果行（map-underscore-to-camel-case 自动映射）。
 * 仅 {@link com.superprogrammer.knowledge.mapper.RagAnswerCacheMapper#searchCandidates} 内部使用。
 */
@Data
public class CacheCandidateRow {

    private Long id;

    private String queryCanonical;

    /** pgvector cosine 距离 [0,2]，sim = 1 - distance */
    private Double cosineDistance;

    /** CachedPayload JSON */
    private String answer;

    private String provenanceNodeIds;

    private String evidenceHashes;

    private String permissionSignature;

    private String keyEmbeddingModel;

    private String rankingConfigVersion;

    private String pipelineVersion;

    private String promptVersion;

    private String knowledgeSnapshot;

    private Float confidence;
}
