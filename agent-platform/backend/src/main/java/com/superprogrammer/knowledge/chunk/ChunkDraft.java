package com.superprogrammer.knowledge.chunk;

import com.superprogrammer.knowledge.service.internal.SectionLocator;

/** 尚未落库的结构化 Chunk；ordinal/邻居均为同一 Section 内的稳定序号。 */
public record ChunkDraft(
        String granularity,
        String chunkType,
        String sourceSectionId,
        String content,
        int tokenCount,
        int ordinal,
        Integer previousOrdinal,
        Integer nextOrdinal,
        SectionLocator locator) {
}
