package com.superprogrammer.knowledge.opensearch;

import java.util.List;

public record OpenSearchChunkDocument(
        Long tenantId, Long knowledgeBaseId, Long documentId, Long documentVersionId, Long nodeId,
        List<String> aclTokens, String status, String contentHash, String contextHash,
        String pipelineVersion, String sparseText, float[] denseVector) {

    public OpenSearchChunkDocument {
        aclTokens = aclTokens == null ? List.of() : List.copyOf(aclTokens);
        denseVector = denseVector == null ? new float[0] : denseVector.clone();
    }

    @Override
    public float[] denseVector() { return denseVector.clone(); }
}
