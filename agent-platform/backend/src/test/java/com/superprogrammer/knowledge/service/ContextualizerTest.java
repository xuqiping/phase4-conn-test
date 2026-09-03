package com.superprogrammer.knowledge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.knowledge.entity.KnowledgeDocument;
import com.superprogrammer.knowledge.entity.KnowledgeDocumentVersion;
import com.superprogrammer.knowledge.entity.KnowledgeNode;
import com.superprogrammer.knowledge.util.HashUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

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

    // ---- WP3 C4：定位语行与新 contextHash 公式 ----

    @Test
    void contextualTextPresent_locatorLineInjectedAndHashChanged() {
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setTitle("差旅制度");
        KnowledgeNode node = new KnowledgeNode();
        node.setTitle("交通费标准");
        node.setContent("高铁二等座可以报销。");
        node.setContextualText("第2章 交通费下的金额标准表");

        Contextualizer.ContextualContent with = contextualizer.contextualize(doc, new KnowledgeDocumentVersion(), node);
        node.setContextualText(null);
        Contextualizer.ContextualContent without = contextualizer.contextualize(doc, new KnowledgeDocumentVersion(), node);

        // 拼接顺序：规则前缀 → 定位语行 → 原文
        assertEquals("文档：差旅制度\n版本：未标注\n标题路径：未标注\n所属背景：交通费标准\n"
                + "定位语：第2章 交通费下的金额标准表\n原文：高铁二等座可以报销。", with.text());
        assertNotEquals(without.contextHash(), with.contextHash());   // 新公式=sha256(规则前缀+定位语+原文)
    }

    @Test
    void contextualTextNull_byteIdenticalLegacy() {
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setTitle("差旅制度");
        KnowledgeNode node = new KnowledgeNode();
        node.setTitle("交通费标准");
        node.setContent("高铁二等座可以报销。");

        // 存量行（contextual_text NULL）→ 文本与旧公式逐字节一致（存量 embedding/job 复校零迁移）
        assertEquals("文档：差旅制度\n版本：未标注\n标题路径：未标注\n所属背景：交通费标准\n原文：高铁二等座可以报销。",
                contextualizer.contextualize(doc, new KnowledgeDocumentVersion(), node).text());
    }
}
