package com.superprogrammer.knowledge.opensearch;

import java.util.Locale;

public record KnowledgeIndexSchema(int embeddingDimension, String pipelineVersion, String snapshotId) {

    public KnowledgeIndexSchema {
        if (embeddingDimension <= 0) throw new IllegalArgumentException("embeddingDimension must be positive");
        pipelineVersion = token(pipelineVersion, "pipelineVersion");
        snapshotId = token(snapshotId, "snapshotId");
    }

    public String physicalIndexName(long knowledgeBaseId) {
        if (knowledgeBaseId <= 0) throw new IllegalArgumentException("knowledgeBaseId must be positive");
        return "kb-" + knowledgeBaseId + "-chunks-" + snapshotId + "-" + pipelineVersion;
    }

    public void validateEmbeddingDimension(int actualDimension) {
        if (actualDimension != embeddingDimension) {
            throw new IllegalArgumentException("embedding dimension mismatch: expected "
                    + embeddingDimension + ", actual " + actualDimension);
        }
    }

    public String mappingJson() {
        return """
                {"settings":{"index":{"knn":true}},"mappings":{"dynamic":"strict","properties":{
                "tenantId":{"type":"keyword"},"knowledgeBaseId":{"type":"long"},
                "documentId":{"type":"long"},"documentVersionId":{"type":"long"},
                "nodeId":{"type":"long"},"aclTokens":{"type":"keyword"},
                "status":{"type":"keyword"},"contentHash":{"type":"keyword"},
                "contextHash":{"type":"keyword"},"pipelineVersion":{"type":"keyword"},
                "sparseText":{"type":"text"},
                "denseVector":{"type":"knn_vector","dimension":%d,"method":{"name":"hnsw","space_type":"cosinesimil","engine":"lucene"}}
                }}}
                """.formatted(embeddingDimension).replaceAll("\\s+", "");
    }

    private static String token(String value, String field) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")) {
            throw new IllegalArgumentException(field + " must be a safe version token");
        }
        return value.toLowerCase(Locale.ROOT);
    }
}
