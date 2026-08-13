package com.superprogrammer.knowledge.retrieval;
public record RetrievalCandidate(String id, Long nodeId, Long documentId, String channel, double rawScore,
                                 String title, String content) {
    public RetrievalCandidate(String id, Long nodeId, Long documentId, String channel, double rawScore) {
        this(id, nodeId, documentId, channel, rawScore, null, null);
    }
}
