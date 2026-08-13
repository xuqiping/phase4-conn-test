package com.superprogrammer.knowledge.retrieval;
public record RetrievalCandidate(String id, Long nodeId, Long documentId, String channel, double rawScore) {}
