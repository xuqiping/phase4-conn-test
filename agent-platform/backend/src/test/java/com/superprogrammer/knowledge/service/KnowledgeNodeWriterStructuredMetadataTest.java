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
import com.superprogrammer.knowledge.chunk.ChunkFactory;
import com.superprogrammer.common.metrics.BizMetrics;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import com.superprogrammer.knowledge.entity.KnowledgeIndexJob;
import com.superprogrammer.knowledge.entity.KnowledgeBase;
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
        BizMetrics metrics = mock(BizMetrics.class);
        KnowledgeBaseService kbService = kbService();
        KnowledgeNodeWriter writer = new KnowledgeNodeWriter(
                documentMapper, nodeMapper, jobMapper, objectMapper, ChunkFactory.defaults(), metrics,
                kbService, new Contextualizer(objectMapper));
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
        doc.setOwnerId(4L);
        doc.setAuthorityLevel("OFFICIAL");
        doc.setConfidentialityLevel("CONFIDENTIAL");
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
            assertThat(metadata.path("tenantId").asLong()).isEqualTo(1L);
            assertThat(metadata.path("kbId").asLong()).isEqualTo(8L);
            assertThat(metadata.path("documentId").asLong()).isEqualTo(7L);
            assertThat(metadata.path("versionId").asLong()).isEqualTo(9L);
            assertThat(metadata.path("ownerId").asLong()).isEqualTo(4L);
            assertThat(metadata.path("authorityLevel").asText()).isEqualTo("OFFICIAL");
            assertThat(metadata.path("confidentialityLevel").asText()).isEqualTo("CONFIDENTIAL");
        });
    }

    @Test
    void writerPersistsS1AndC2CompatibilityLevelsWithChunkNeighbors() throws Exception {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "test-chunks");
        assistant.setCurrentNamespace("test-chunks");
        TableInfoHelper.initTableInfo(assistant, KnowledgeDocument.class);
        KnowledgeDocumentMapper documentMapper = mock(KnowledgeDocumentMapper.class);
        KnowledgeNodeMapper nodeMapper = mock(KnowledgeNodeMapper.class);
        KnowledgeIndexJobMapper jobMapper = mock(KnowledgeIndexJobMapper.class);
        ObjectMapper objectMapper = new ObjectMapper();
        BizMetrics metrics = mock(BizMetrics.class);
        KnowledgeBaseService kbService = kbService();
        KnowledgeNodeWriter writer = new KnowledgeNodeWriter(
                documentMapper, nodeMapper, jobMapper, objectMapper, ChunkFactory.defaults(), metrics,
                kbService, new Contextualizer(objectMapper));
        AtomicLong ids = new AtomicLong(20);
        doAnswer(invocation -> {
            invocation.<KnowledgeNode>getArgument(0).setId(ids.incrementAndGet());
            return 1;
        }).when(nodeMapper).insert(any(KnowledgeNode.class));
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setId(7L); doc.setKbId(8L); doc.setCurrentVersionId(9L); doc.setCreatedBy(3L);
        String content = java.util.stream.IntStream.range(0, 12)
                .mapToObj(i -> "段落" + i + "：" + "完整事实。".repeat(60))
                .collect(java.util.stream.Collectors.joining("\n\n"));
        Section section = Section.builder().sectionId("sec-8").nodeType("SECTION")
                .title("安装步骤").titlePath(List.of("手册", "安装步骤")).ordinal(0)
                .content(content).locator(SectionLocator.builder().pageStart(4).pageEnd(4).build()).build();

        writer.writeNodes(doc, 3L, ExtractedDocument.builder().sections(List.of(section)).build(),
                null, List.of("安装摘要"), "{}");

        ArgumentCaptor<KnowledgeNode> captor = ArgumentCaptor.forClass(KnowledgeNode.class);
        verify(nodeMapper, org.mockito.Mockito.atLeast(3)).insert(captor.capture());
        List<KnowledgeNode> nodes = captor.getAllValues();
        KnowledgeNode s1 = nodes.get(0);
        List<KnowledgeNode> children = nodes.subList(1, nodes.size());
        assertThat(s1.getLevel()).isEqualTo("L0");
        assertThat(objectMapper.readTree(s1.getMetadata()).path("granularity").asText()).isEqualTo("S1");
        assertThat(children).allSatisfy(child -> {
            JsonNode metadata = objectMapper.readTree(child.getMetadata());
            assertThat(child.getLevel()).isEqualTo("L2");
            assertThat(child.getParentId()).isEqualTo(s1.getId());
            assertThat(child.getNodeType()).isEqualTo("PARAGRAPH");
            assertThat(child.getVersionId()).isEqualTo(9L);
            assertThat(metadata.path("granularity").asText()).isEqualTo("C2");
            assertThat(metadata.path("chunkerVersion").asText()).isEqualTo("1");
            assertThat(metadata.path("locator").path("pageStart").asInt()).isEqualTo(4);
        });
        assertThat(objectMapper.readTree(children.get(0).getMetadata()).path("nextPath").asText())
                .isEqualTo(children.get(1).getPath());
        assertThat(objectMapper.readTree(children.get(1).getMetadata()).path("previousPath").asText())
                .isEqualTo(children.get(0).getPath());
        verify(metrics).knowledgeChunked("S1", 1);
        verify(metrics).knowledgeChunked("C2", children.size());
        verify(metrics).knowledgeChunkDuration(any(java.time.Duration.class));
    }

    @Test
    void s1SectionsCarryStablePreviousAndNextPaths() throws Exception {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "test-s1-neighbor");
        assistant.setCurrentNamespace("test-s1-neighbor");
        TableInfoHelper.initTableInfo(assistant, KnowledgeDocument.class);
        KnowledgeDocumentMapper documentMapper = mock(KnowledgeDocumentMapper.class);
        KnowledgeNodeMapper nodeMapper = mock(KnowledgeNodeMapper.class);
        KnowledgeIndexJobMapper jobMapper = mock(KnowledgeIndexJobMapper.class);
        ObjectMapper objectMapper = new ObjectMapper();
        KnowledgeNodeWriter writer = new KnowledgeNodeWriter(documentMapper, nodeMapper, jobMapper,
                objectMapper, ChunkFactory.defaults(), mock(BizMetrics.class), kbService(),
                new Contextualizer(objectMapper));
        AtomicLong ids = new AtomicLong(30);
        doAnswer(invocation -> {
            invocation.<KnowledgeNode>getArgument(0).setId(ids.incrementAndGet());
            return 1;
        }).when(nodeMapper).insert(any(KnowledgeNode.class));
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setId(7L); doc.setKbId(8L); doc.setCurrentVersionId(9L); doc.setCreatedBy(3L);
        Section first = Section.builder().sectionId("s1").nodeType("SECTION").title("第一章")
                .content("第一章正文").ordinal(0).build();
        Section second = Section.builder().sectionId("s2").nodeType("SECTION").title("第二章")
                .content("第二章正文").ordinal(1).build();

        writer.writeNodes(doc, 3L, ExtractedDocument.builder().sections(List.of(first, second)).build(),
                null, List.of("第一章摘要", "第二章摘要"), "{}");

        ArgumentCaptor<KnowledgeNode> captor = ArgumentCaptor.forClass(KnowledgeNode.class);
        verify(nodeMapper, org.mockito.Mockito.times(4)).insert(captor.capture());
        List<KnowledgeNode> s1Nodes = captor.getAllValues().stream()
                .filter(node -> "L0".equals(node.getLevel())).toList();
        assertThat(objectMapper.readTree(s1Nodes.get(0).getMetadata()).path("nextPath").asText())
                .isEqualTo("/L0-1");
        assertThat(objectMapper.readTree(s1Nodes.get(1).getMetadata()).path("previousPath").asText())
                .isEqualTo("/L0-0");
    }

    @Test
    void c2CreatesContextualIndexJobWithStableIdempotencyKey() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "test-c2-index");
        assistant.setCurrentNamespace("test-c2-index");
        TableInfoHelper.initTableInfo(assistant, KnowledgeDocument.class);
        KnowledgeDocumentMapper documentMapper = mock(KnowledgeDocumentMapper.class);
        KnowledgeNodeMapper nodeMapper = mock(KnowledgeNodeMapper.class);
        KnowledgeIndexJobMapper jobMapper = mock(KnowledgeIndexJobMapper.class);
        ObjectMapper objectMapper = new ObjectMapper();
        KnowledgeNodeWriter writer = new KnowledgeNodeWriter(documentMapper, nodeMapper, jobMapper,
                objectMapper, ChunkFactory.defaults(), mock(BizMetrics.class), kbService(),
                new Contextualizer(objectMapper));
        AtomicLong ids = new AtomicLong(40);
        doAnswer(invocation -> {
            invocation.<KnowledgeNode>getArgument(0).setId(ids.incrementAndGet());
            return 1;
        }).when(nodeMapper).insert(any(KnowledgeNode.class));
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setId(7L); doc.setKbId(8L); doc.setCurrentVersionId(9L); doc.setCreatedBy(3L);
        doc.setTitle("部署手册");
        Section section = Section.builder().sectionId("c2-1").nodeType("SECTION")
                .title("环境准备").titlePath(List.of("安装", "环境准备"))
                .content("安装 Java 17。") .ordinal(0).build();

        writer.writeNodes(doc, 3L, ExtractedDocument.builder().parserVersion("5.2")
                        .sections(List.of(section)).build(),
                null, List.of("环境准备摘要"), "{}");

        ArgumentCaptor<KnowledgeIndexJob> jobs = ArgumentCaptor.forClass(KnowledgeIndexJob.class);
        verify(jobMapper, org.mockito.Mockito.times(2)).insertNodeJobIgnoreConflict(jobs.capture());
        KnowledgeIndexJob c2Job = jobs.getAllValues().stream()
                .filter(job -> job.getNodeId().equals(42L)).findFirst().orElseThrow();
        assertThat(c2Job.getContextHash()).isNotBlank().doesNotContain("placeholder");
        assertThat(c2Job.getVersionId()).isEqualTo(9L);
        assertThat(c2Job.getParserVersion()).isEqualTo("5.2");
        assertThat(c2Job.getChunkerVersion()).isEqualTo("1");
        assertThat(c2Job.getEmbeddingModel()).isEqualTo("test-embedding-model");
        assertThat(c2Job.getPipelineVersion()).isEqualTo("rag-index-v1");
        assertThat(c2Job.getIdempotencyKey()).isEqualTo(com.superprogrammer.knowledge.util.HashUtil.sha256(
                c2Job.getNodeId() + ":" + c2Job.getContentHash() + ":" + c2Job.getContextHash() + ":9:5.2:1:"
                        + c2Job.getEmbeddingModel() + ":" + c2Job.getPipelineVersion() + ":UPSERT"));
    }

    private KnowledgeBaseService kbService() {
        KnowledgeBaseService service = mock(KnowledgeBaseService.class);
        KnowledgeBase kb = new KnowledgeBase();
        kb.setEmbeddingModel("test-embedding-model");
        org.mockito.Mockito.when(service.ensure(org.mockito.ArgumentMatchers.anyLong())).thenReturn(kb);
        return service;
    }
}
