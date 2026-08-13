package com.superprogrammer.knowledge.dto;

public record KnowledgeIndexStatusVO(Long knowledgeBaseId, String state, String readAlias,
                                     String writeAlias, String activeSnapshotId, String previousSnapshotId) {}
