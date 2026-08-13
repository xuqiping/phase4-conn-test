package com.superprogrammer.knowledge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.knowledge.entity.KnowledgeDocument;
import com.superprogrammer.knowledge.entity.KnowledgeDocumentVersion;
import com.superprogrammer.knowledge.entity.KnowledgeNode;
import com.superprogrammer.knowledge.util.HashUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ContextualizerTest {

    private final Contextualizer contextualizer = new Contextualizer(new ObjectMapper());

    @Test
    void buildsStableContextFromDocumentVersionPathBackgroundAndRawContent() {
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setTitle("差旅制度");
        KnowledgeDocumentVersion version = new KnowledgeDocumentVersion();
        version.setVersionNo(3);
        KnowledgeNode node = new KnowledgeNode();
        node.setTitle("交通费标准");
        node.setContent("高铁二等座可以报销。");
        node.setContentHash(HashUtil.sha256(node.getContent()));
        node.setMetadata("{\"titlePath\":[\"费用报销\",\"交通费\"],\"parentSectionId\":\"chapter-2\"}");

        Contextualizer.ContextualContent first = contextualizer.contextualize(doc, version, node);
        Contextualizer.ContextualContent second = contextualizer.contextualize(doc, version, node);

        assertEquals("文档：差旅制度\n版本：v3\n标题路径：费用报销 > 交通费\n所属背景：交通费标准\n原文：高铁二等座可以报销。", first.text());
        assertEquals(node.getContentHash(), first.contentHash());
        assertEquals(HashUtil.sha256(first.text()), first.contextHash());
        assertEquals(first, second);
    }

    @Test
    void missingOptionalMetadataUsesExplicitFallbackWithoutNullLiteral() {
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setTitle("安全手册");
        KnowledgeDocumentVersion version = new KnowledgeDocumentVersion();
        KnowledgeNode node = new KnowledgeNode();
        node.setContent("禁止共享密钥。");

        Contextualizer.ContextualContent result = contextualizer.contextualize(doc, version, node);

        assertEquals("文档：安全手册\n版本：未标注\n标题路径：未标注\n所属背景：未标注\n原文：禁止共享密钥。", result.text());
        assertEquals(HashUtil.sha256("禁止共享密钥。"), result.contentHash());
        assertFalse(result.text().contains("null"));
    }
}
