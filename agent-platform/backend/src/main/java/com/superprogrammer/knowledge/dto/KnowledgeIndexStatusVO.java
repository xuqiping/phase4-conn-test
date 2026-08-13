package com.superprogrammer.knowledge.dto;

public record KnowledgeIndexStatusVO(Long knowledgeBaseId, String state, String readAlias,
                                     String writeAlias, String activeSnapshotId, String previousSnapshotId,
                                     String rebuildSnapshotId, Integer total, Integer completed,
                                     Integer failed, Integer cancelled) {
    public KnowledgeIndexStatusVO(Long knowledgeBaseId, String state, String readAlias, String writeAlias,
                                  String activeSnapshotId, String previousSnapshotId) {
        this(knowledgeBaseId, state, readAlias, writeAlias, activeSnapshotId, previousSnapshotId,
                null, null, null, null, null);
    }
}
