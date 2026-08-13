package com.superprogrammer.knowledge.chunk;

import com.superprogrammer.knowledge.service.internal.Section;
import com.superprogrammer.knowledge.util.TokenEstimator;

import java.util.ArrayList;
import java.util.List;

/** 普通文档按完整段落聚合，目标 300～600 token，相邻块保留不超过 100 token 的完整段落 overlap。 */
public class NormalDocumentChunkStrategy implements ChunkStrategy {

    static final int MAX_TOKENS = 600;
    static final int OVERLAP_MAX_TOKENS = 100;

    @Override
    public boolean supports(Section section) {
        return true;
    }

    @Override
    public List<ChunkDraft> chunk(Section section) {
        if (section.getContent() == null || section.getContent().isBlank()) {
            return List.of();
        }
        List<String> paragraphs = splitParagraphs(section.getContent());
        List<String> contents = new ArrayList<>();
        List<String> current = new ArrayList<>();
        for (String paragraph : paragraphs) {
            if (!current.isEmpty() && tokens(join(current, paragraph)) > MAX_TOKENS) {
                contents.add(String.join("\n\n", current));
                current = overlapTail(current);
            }
            if (tokens(paragraph) > MAX_TOKENS) {
                if (!current.isEmpty()) {
                    contents.add(String.join("\n\n", current));
                    current = new ArrayList<>();
                }
                contents.addAll(hardSplit(paragraph));
            } else {
                current.add(paragraph);
            }
        }
        if (!current.isEmpty()) {
            String tail = String.join("\n\n", current);
            if (contents.isEmpty() || !contents.get(contents.size() - 1).equals(tail)) {
                contents.add(tail);
            }
        }
        return drafts(section, contents);
    }

    private List<String> splitParagraphs(String content) {
        return java.util.Arrays.stream(content.split("(?:\\r?\\n){2,}"))
                .map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    private List<String> overlapTail(List<String> current) {
        List<String> overlap = new ArrayList<>();
        int total = 0;
        for (int i = current.size() - 1; i >= 0; i--) {
            int paragraphTokens = tokens(current.get(i));
            if (total + paragraphTokens > OVERLAP_MAX_TOKENS) {
                break;
            }
            overlap.add(0, current.get(i));
            total += paragraphTokens;
        }
        return overlap;
    }

    private List<String> hardSplit(String paragraph) {
        int maxChars = MAX_TOKENS * 4;
        List<String> pieces = new ArrayList<>();
        for (int start = 0; start < paragraph.length(); start += maxChars) {
            pieces.add(paragraph.substring(start, Math.min(paragraph.length(), start + maxChars)));
        }
        return pieces;
    }

    private List<ChunkDraft> drafts(Section section, List<String> contents) {
        List<ChunkDraft> drafts = new ArrayList<>();
        for (int i = 0; i < contents.size(); i++) {
            String content = contents.get(i);
            drafts.add(new ChunkDraft("C2", "PARAGRAPH", section.getSectionId(), content,
                    tokens(content), i, i == 0 ? null : i - 1,
                    i + 1 < contents.size() ? i + 1 : null, section.getLocator()));
        }
        return drafts;
    }

    private String join(List<String> current, String paragraph) {
        return current.isEmpty() ? paragraph : String.join("\n\n", current) + "\n\n" + paragraph;
    }

    private int tokens(String content) {
        return TokenEstimator.estimate(content);
    }
}
