package com.superprogrammer.knowledge.chunk;

import com.superprogrammer.knowledge.service.internal.Section;
import org.springframework.stereotype.Component;

import java.util.List;

/** 按 Section 类型选择首个匹配策略；默认策略必须放在列表末尾。 */
@Component
public class ChunkFactory {

    private final List<ChunkStrategy> strategies;

    public ChunkFactory(List<ChunkStrategy> strategies) {
        this.strategies = List.copyOf(strategies);
    }

    public ChunkFactory() {
        this(defaultStrategies());
    }

    public static ChunkFactory defaults() {
        return new ChunkFactory(defaultStrategies());
    }

    private static List<ChunkStrategy> defaultStrategies() {
        return List.of(
                new AtomicTypeChunkStrategy(List.of("CLAUSE"), "C2", "CLAUSE"),
                new AtomicTypeChunkStrategy(List.of("FAQ"), "C2", "FAQ"),
                new AtomicTypeChunkStrategy(List.of("LIST"), "C2", "LIST"),
                new AtomicTypeChunkStrategy(List.of("PROCEDURE"), "C2", "PROCEDURE"),
                new AtomicTypeChunkStrategy(List.of("TABLE_ROW"), "E3", "TABLE_ROW"),
                new AtomicTypeChunkStrategy(List.of("IMAGE", "VISUAL_REGION"), "E3", "VISUAL_REGION"),
                new AtomicTypeChunkStrategy(List.of("PAGE"), "C2", "PDF_PAGE"),
                new NormalDocumentChunkStrategy());
    }

    public List<ChunkDraft> chunk(Section section) {
        return strategies.stream()
                .filter(strategy -> strategy.supports(section))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no chunk strategy for section"))
                .chunk(section);
    }
}
