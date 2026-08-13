package com.superprogrammer.knowledge.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.knowledge.entity.KnowledgeDocument;
import com.superprogrammer.knowledge.entity.KnowledgeDocumentVersion;
import com.superprogrammer.knowledge.entity.KnowledgeNode;
import com.superprogrammer.knowledge.util.HashUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** 为索引文本补充稳定、最小且不含权限治理信息的文档背景。 */
@Component
@RequiredArgsConstructor
public class Contextualizer {

    private static final String UNKNOWN = "未标注";

    private final ObjectMapper objectMapper;

    public ContextualContent contextualize(KnowledgeDocument document,
                                            KnowledgeDocumentVersion version,
                                            KnowledgeNode node) {
        String rawContent = value(node == null ? null : node.getContent(), "");
        String text = "文档：" + value(document == null ? null : document.getTitle(), UNKNOWN)
                + "\n版本：" + versionLabel(version)
                + "\n标题路径：" + titlePath(node)
                + "\n所属背景：" + value(node == null ? null : node.getTitle(), UNKNOWN)
                + "\n原文：" + rawContent;
        String contentHash = node != null && hasText(node.getContentHash())
                ? node.getContentHash() : HashUtil.sha256(rawContent);
        return new ContextualContent(text, contentHash, HashUtil.sha256(text));
    }

    private String versionLabel(KnowledgeDocumentVersion version) {
        if (version == null) {
            return UNKNOWN;
        }
        if (version.getVersionNo() != null) {
            return "v" + version.getVersionNo();
        }
        return version.getId() == null ? UNKNOWN : "id:" + version.getId();
    }

    private String titlePath(KnowledgeNode node) {
        if (node == null || !hasText(node.getMetadata())) {
            return UNKNOWN;
        }
        try {
            JsonNode titlePath = objectMapper.readTree(node.getMetadata()).get("titlePath");
            if (titlePath == null || titlePath.isNull()) {
                return UNKNOWN;
            }
            if (titlePath.isArray()) {
                List<String> parts = new ArrayList<>();
                titlePath.forEach(part -> {
                    if (part.isTextual() && hasText(part.asText())) {
                        parts.add(part.asText().trim());
                    }
                });
                return parts.isEmpty() ? UNKNOWN : String.join(" > ", parts);
            }
            return hasText(titlePath.asText()) ? titlePath.asText().trim() : UNKNOWN;
        } catch (Exception ignored) {
            return UNKNOWN;
        }
    }

    private static String value(String value, String fallback) {
        return hasText(value) ? value.trim() : fallback;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public record ContextualContent(String text, String contentHash, String contextHash) {
    }
}
