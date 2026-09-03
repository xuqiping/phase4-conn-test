package com.superprogrammer.knowledge.opensearch;

import java.util.List;

/**
 * @param contextualText C4 LLM 定位语（V171 起随 chunk 双写；null=纯规则前缀=存量口径）
 */
public record OpenSearchChunkDocument(
        Long tenantId, Long knowledgeBaseId, Long documentId, Long documentVersionId, Long nodeId,
        List<String> aclTokens, String status, String contentHash, String contextHash,
        String contextualText, String pipelineVersion, String sparseText, float[] denseVector) {

    public OpenSearchChunkDocument {
        aclTokens = aclTokens == null ? List.of() : List.copyOf(aclTokens);
        denseVector = denseVector == null ? new float[0] : denseVector.clone();
    }

    @Override
    public float[] denseVector() { return denseVector.clone(); }
}
