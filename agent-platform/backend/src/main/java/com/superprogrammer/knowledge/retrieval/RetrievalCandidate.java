package com.superprogrammer.knowledge.retrieval;
public record RetrievalCandidate(String id, Long nodeId, Long documentId, String channel, double rawScore,
                                 String title, String content, String contentHash) {
    public RetrievalCandidate(String id, Long nodeId, Long documentId, String channel, double rawScore) {
        this(id, nodeId, documentId, channel, rawScore, null, null, null);
    }
    public RetrievalCandidate(String id, Long nodeId, Long documentId, String channel, double rawScore,
                              String title, String content) {
        this(id, nodeId, documentId, channel, rawScore, title, content, null);
    }
}
