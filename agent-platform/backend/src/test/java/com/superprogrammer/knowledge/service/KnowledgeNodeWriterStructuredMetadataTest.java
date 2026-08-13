package com.superprogrammer.knowledge.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.knowledge.entity.KnowledgeDocument;
import com.superprogrammer.knowledge.entity.KnowledgeNode;
import com.superprogrammer.knowledge.mapper.KnowledgeDocumentMapper;
import com.superprogrammer.knowledge.mapper.KnowledgeIndexJobMapper;
import com.superprogrammer.knowledge.mapper.KnowledgeNodeMapper;
import com.superprogrammer.knowledge.service.internal.ExtractedDocument;
import com.superprogrammer.knowledge.service.internal.Section;
import com.superprogrammer.knowledge.service.internal.SectionLocator;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class KnowledgeNodeWriterStructuredMetadataTest {

    @Test
    void sectionHierarchyAndLocatorAreMergedIntoBothNodeLevels() throws Exception {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "test");
        assistant.setCurrentNamespace("test");
        TableInfoHelper.initTableInfo(assistant, KnowledgeDocument.class);
        KnowledgeDocumentMapper documentMapper = mock(KnowledgeDocumentMapper.class);
        KnowledgeNodeMapper nodeMapper = mock(KnowledgeNodeMapper.class);
        KnowledgeIndexJobMapper jobMapper = mock(KnowledgeIndexJobMapper.class);
        ObjectMapper objectMapper = new ObjectMapper();
        KnowledgeNodeWriter writer = new KnowledgeNodeWriter(documentMapper, nodeMapper, jobMapper, objectMapper);
        AtomicLong ids = new AtomicLong(10);
        doAnswer(invocation -> {
            invocation.<KnowledgeNode>getArgument(0).setId(ids.incrementAndGet());
            return 1;
        }).when(nodeMapper).insert(any(KnowledgeNode.class));

        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setId(7L);
        doc.setKbId(8L);
        doc.setCurrentVersionId(9L);
        doc.setCreatedBy(3L);
        Section section = Section.builder()
                .sectionId("sec-7")
                .nodeType("CLAUSE")
                .title("退款条件")
                .titlePath(List.of("售后政策", "退款条件"))
                .ordinal(2)
                .content("七天内可以退款")
                .locator(SectionLocator.builder().pageStart(3).pageEnd(3).readingOrder(2).build())
                .build();

        writer.writeNodes(doc, 3L,
                ExtractedDocument.builder().sections(List.of(section)).build(),
                null, List.of("退款摘要"), "{\"fileRef\":\"/api/files/source.pdf\"}");

        ArgumentCaptor<KnowledgeNode> captor = ArgumentCaptor.forClass(KnowledgeNode.class);
        verify(nodeMapper, org.mockito.Mockito.times(2)).insert(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(node -> {
            JsonNode metadata = objectMapper.readTree(node.getMetadata());
            assertThat(metadata.path("fileRef").asText()).isEqualTo("/api/files/source.pdf");
            assertThat(metadata.path("sectionId").asText()).isEqualTo("sec-7");
            assertThat(metadata.path("titlePath").get(1).asText()).isEqualTo("退款条件");
            assertThat(metadata.path("locator").path("pageStart").asInt()).isEqualTo(3);
            assertThat(node.getNodeType()).isEqualTo("CLAUSE");
            assertThat(node.getVersionId()).isEqualTo(9L);
        });
    }
}
