package com.superprogrammer.knowledge.retrieval;

/** 候选统一面：id=nodeId 字符串（通道内唯一）；title/content 供 CoverageVerifier 覆盖判定。 */
public record RetrievalCandidate(String id, Long nodeId, Long documentId, String channel, double rawScore,
                                 String title, String content, String contentHash)
        implements com.superprogrammer.knowledge.context.CoverageVerifier.CandidateText {
    public RetrievalCandidate(String id, Long nodeId, Long documentId, String channel, double rawScore) {
        this(id, nodeId, documentId, channel, rawScore, null, null, null);
    }
    public RetrievalCandidate(String id, Long nodeId, Long documentId, String channel, double rawScore,
                              String title, String content) {
        this(id, nodeId, documentId, channel, rawScore, title, content, null);
    }
}
