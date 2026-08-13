package com.superprogrammer.knowledge.chunk;

import com.superprogrammer.knowledge.service.internal.Section;

import java.util.List;

/** 文档类型分块策略；不负责数据库写入。 */
public interface ChunkStrategy {

    boolean supports(Section section);

    List<ChunkDraft> chunk(Section section);
}
