package com.superprogrammer.knowledge.opensearch;

import java.util.List;

/**
 * @param contextualText C4 LLM 定位语（V171 起随 chunk 双写；null=纯规则前缀=存量口径）
 * @param modality 内容模态（WP5 Step2，V171 nodes.modality 透传；null=存量=TEXT。Step3 检索按此过滤）
 */
public record OpenSearchChunkDocument(
        Long tenantId, Long knowledgeBaseId, Long documentId, Long documentVersionId, Long nodeId,
        List<String> aclTokens, String status, String contentHash, String contextHash,
        String contextualText, String modality, String pipelineVersion, String sparseText, float[] denseVector) {

    public OpenSearchChunkDocument {
        aclTokens = aclTokens == null ? List.of() : List.copyOf(aclTokens);
        denseVector = denseVector == null ? new float[0] : denseVector.clone();
    }

    @Override
    public float[] denseVector() { return denseVector.clone(); }
}
