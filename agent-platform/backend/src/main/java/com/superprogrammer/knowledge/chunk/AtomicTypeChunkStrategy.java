package com.superprogrammer.knowledge.chunk;

import com.superprogrammer.knowledge.service.internal.Section;
import com.superprogrammer.knowledge.util.TokenEstimator;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 条款、FAQ、逻辑行和视觉区域等原子对象策略；仅超出安全上限时硬切。 */
public class AtomicTypeChunkStrategy implements ChunkStrategy {

    private static final int MAX_TOKENS = 600;
    private final Set<String> supportedTypes;
    private final String granularity;
    private final String chunkType;

    public AtomicTypeChunkStrategy(List<String> supportedTypes, String granularity, String chunkType) {
        this.supportedTypes = Set.copyOf(supportedTypes);
        this.granularity = granularity;
        this.chunkType = chunkType;
    }

    @Override
    public boolean supports(Section section) {
        return section != null && supportedTypes.contains(section.getNodeType());
    }

    @Override
    public List<ChunkDraft> chunk(Section section) {
        if (section.getContent() == null || section.getContent().isBlank()) {
            return List.of();
        }
        List<String> contents = hardSplit(section.getContent().strip());
        List<ChunkDraft> drafts = new ArrayList<>();
        for (int i = 0; i < contents.size(); i++) {
            String content = contents.get(i);
            drafts.add(new ChunkDraft(granularity, chunkType, section.getSectionId(), content,
                    TokenEstimator.estimate(content), i, i == 0 ? null : i - 1,
                    i + 1 < contents.size() ? i + 1 : null, section.getLocator()));
        }
        return drafts;
    }

    private List<String> hardSplit(String content) {
        if (TokenEstimator.estimate(content) <= MAX_TOKENS) {
            return List.of(content);
        }
        int maxChars = MAX_TOKENS * 4;
        List<String> pieces = new ArrayList<>();
        for (int start = 0; start < content.length(); start += maxChars) {
            pieces.add(content.substring(start, Math.min(content.length(), start + maxChars)));
        }
        return pieces;
    }
}
