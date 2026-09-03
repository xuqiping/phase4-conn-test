// agent-platform/backend/src/test/java/com/superprogrammer/knowledge/service/ContextualRebuildServiceTest.java
package com.superprogrammer.knowledge.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.knowledge.entity.KnowledgeBase;
import com.superprogrammer.knowledge.entity.KnowledgeDocument;
import com.superprogrammer.knowledge.entity.KnowledgeIndexJob;
import com.superprogrammer.knowledge.entity.KnowledgeNode;
import com.superprogrammer.knowledge.mapper.KnowledgeDocumentMapper;
import com.superprogrammer.knowledge.mapper.KnowledgeDocumentVersionMapper;
import com.superprogrammer.knowledge.mapper.KnowledgeIndexJobMapper;
import com.superprogrammer.knowledge.mapper.KnowledgeNodeMapper;
import com.superprogrammer.knowledge.util.HashUtil;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WP3 C4 存量可选重建：编排（ATTACHMENT 豁免/中断可续/tx 接线）+ 事务段
 * （定位语写回+新公式 hash+全指纹 REINDEX job CTX_LLM_V1）单测。
 */
class ContextualRebuildServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeAll
    static void initMpLambdaCache() {
        // 纯 Mockito 测 LambdaQueryWrapper 须先填 MP lambda 缓存（承 AssetProjectServiceTest 范式）
        for (Class<?> entity : List.of(KnowledgeDocument.class, KnowledgeNode.class)) {
            MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(),
                    "test-ctx-rebuild-" + entity.getSimpleName());
            assistant.setCurrentNamespace("test-ctx-rebuild-" + entity.getSimpleName());
            TableInfoHelper.initTableInfo(assistant, entity);
        }
    }

    @Test
    void apply_skipsAttachmentAndDoneDocs_llmPerPendingDoc_txWiredWithEstimates() {
        KnowledgeDocumentMapper documentMapper = mock(KnowledgeDocumentMapper.class);
        KnowledgeNodeMapper nodeMapper = mock(KnowledgeNodeMapper.class);
        KnowledgeDocumentVersionMapper versionMapper = mock(KnowledgeDocumentVersionMapper.class);
        KnowledgeBaseService kbService = mock(KnowledgeBaseService.class);
        LlmContextualizer llm = mock(LlmContextualizer.class);
        ContextualRebuildTxService tx = mock(ContextualRebuildTxService.class);
        ContextualRebuildService service = new ContextualRebuildService(
                documentMapper, versionMapper, nodeMapper, kbService, llm, tx, objectMapper);

        KnowledgeBase kb = new KnowledgeBase();
        kb.setEmbeddingModel("emb-m");
        when(kbService.ensure(7L)).thenReturn(kb);
        KnowledgeDocument pending = doc(11L, null);                 // 待重建（普通文档）
        KnowledgeDocument attachment = doc(12L, "ATTACHMENT");      // 附件：描述召回豁免
        KnowledgeDocument done = doc(13L, null);                    // 中断可续：整文档已完成
        when(documentMapper.selectList(any())).thenReturn(List.of(pending, attachment, done));
        List<KnowledgeNode> pendingNodes = List.of(
                node(21L, "/L0-0/L2-0", "{\"granularity\":\"C2\",\"chunkerVersion\":\"1\"}", null),
                node(22L, "/L0-0/L2-1", "{\"granularity\":\"C2\",\"chunkerVersion\":\"1\"}", null));
        List<KnowledgeNode> doneNodes = List.of(
                node(23L, "/L0-0/L2-0", null, "既有定位语"));
        // apply 只对 parseable 文档调 loadActiveL2：pending 先、done 后（ATTACHMENT 不触节点查询）
        when(nodeMapper.selectList(any())).thenReturn(pendingNodes).thenReturn(doneNodes);
        com.superprogrammer.knowledge.entity.KnowledgeDocumentVersion version =
                new com.superprogrammer.knowledge.entity.KnowledgeDocumentVersion();
        version.setParserVersion("5.2");
        when(versionMapper.selectById(9L)).thenReturn(version);
        when(llm.generateLocators(eq(pending), eq("差旅制度摘要"), anyList(), eq(42L)))
                .thenReturn(Map.of("/L0-0/L2-0", "定位语A", "/L0-0/L2-1", "定位语B"));
        when(tx.applyContextualLocators(eq(pending), eq("emb-m"), anyString(), any(), anyList()))
                .thenReturn(2);

        var vo = service.apply(7L);

        assertThat(vo.appliedDocs()).isEqualTo(1);
        assertThat(vo.skippedDone()).isEqualTo(1);
        assertThat(vo.skippedAttachment()).isEqualTo(1);
        assertThat(vo.enqueuedJobs()).isEqualTo(2);
        // ATTACHMENT 不触发 LLM/节点查询；done 文档 LLM 不重跑（中断可续=doc 粒度幂等）
        verify(nodeMapper, times(2)).selectList(any());
        verify(llm, times(1)).generateLocators(any(), any(), anyList(), any());
        verify(tx, times(1)).applyContextualLocators(any(), anyString(), anyString(), any(), anyList());
        // briefs 来自 DB 节点（path/标题/首行）
        ArgumentCaptor<List<LlmContextualizer.ChunkBrief>> briefs = ArgumentCaptor.captor();
        verify(llm).generateLocators(eq(pending), any(), briefs.capture(), eq(42L));
        assertThat(briefs.getValue()).extracting(LlmContextualizer.ChunkBrief::path)
                .containsExactly("/L0-0/L2-0", "/L0-0/L2-1");
    }

    @Test
    void estimate_countsDocsAndChunksAndAttachmentExempt() {
        KnowledgeDocumentMapper documentMapper = mock(KnowledgeDocumentMapper.class);
        KnowledgeNodeMapper nodeMapper = mock(KnowledgeNodeMapper.class);
        KnowledgeDocumentVersionMapper versionMapper = mock(KnowledgeDocumentVersionMapper.class);
        KnowledgeBaseService kbService = mock(KnowledgeBaseService.class);
        ContextualRebuildService service = new ContextualRebuildService(
                documentMapper, versionMapper, nodeMapper, kbService,
                mock(LlmContextualizer.class), mock(ContextualRebuildTxService.class), objectMapper);
        when(documentMapper.selectList(any())).thenReturn(
                List.of(doc(11L, null), doc(12L, "ATTACHMENT"), doc(13L, null)));
        when(nodeMapper.selectList(any())).thenReturn(
                List.of(node(21L, "/L0-0/L2-0", "{\"granularity\":\"C2\"}", null),
                        node(22L, "/L0-0/L2-1", "{\"granularity\":\"E3\"}", null)),
                List.of(node(23L, "/L0-0/L2-0", "{\"granularity\":\"E3\"}", null)));

        var vo = service.estimate(7L);

        assertThat(vo.dryRun()).isTrue();
        assertThat(vo.docCount()).isEqualTo(2);          // ATTACHMENT 不计参与文档
        assertThat(vo.chunkCount()).isEqualTo(3);        // C2/E3 全计
        assertThat(vo.llmCallCount()).isEqualTo(2);      // = 待重建文档数
        assertThat(vo.skippedAttachment()).isEqualTo(1);
    }

    @Test
    void txWritesLocatorAndNewHash_enqueuesFullFingerprintReindexJobs() {
        KnowledgeNodeMapper nodeMapper = mock(KnowledgeNodeMapper.class);
        KnowledgeIndexJobMapper jobMapper = mock(KnowledgeIndexJobMapper.class);
        when(jobMapper.insertNodeJobIgnoreConflict(any(KnowledgeIndexJob.class))).thenReturn(1);
        ContextualRebuildTxService tx = new ContextualRebuildTxService(
                nodeMapper, jobMapper, new Contextualizer(objectMapper), objectMapper);

        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setId(11L); doc.setKbId(7L); doc.setTitle("差旅制度"); doc.setCurrentVersionId(9L);
        KnowledgeNode hit = node(21L, "/L0-0/L2-0", "{\"granularity\":\"C2\",\"chunkerVersion\":\"1\"}", null);
        KnowledgeNode miss = node(22L, "/L0-0/L2-1", "{\"granularity\":\"C2\",\"chunkerVersion\":\"1\"}", null);
        hit.setVersionId(9L);
        miss.setVersionId(9L);
        hit.setContent("高铁二等座可以报销。");
        miss.setContent("打车需附行程单。");

        int enqueued = tx.applyContextualLocators(doc, "emb-m", "5.2",
                Map.of("/L0-0/L2-0", "第2章 交通费下的金额标准表"), List.of(hit, miss));

        assertThat(enqueued).isEqualTo(2);
        // 命中节点：定位语落库 + hash=新公式（含定位语行）；缺席节点：不落定位语 + hash=旧公式（legacy 逐字节）
        ArgumentCaptor<KnowledgeNode> nodes = ArgumentCaptor.forClass(KnowledgeNode.class);
        verify(nodeMapper, times(2)).updateById(nodes.capture());
        KnowledgeNode updatedHit = nodes.getAllValues().stream()
                .filter(n -> n.getId().equals(21L)).findFirst().orElseThrow();
        KnowledgeNode updatedMiss = nodes.getAllValues().stream()
                .filter(n -> n.getId().equals(22L)).findFirst().orElseThrow();
        assertThat(updatedHit.getContextualText()).isEqualTo("第2章 交通费下的金额标准表");
        assertThat(updatedHit.getContextHash()).isEqualTo(HashUtil.sha256(
                "文档：差旅制度\n版本：id:9\n标题路径：未标注\n所属背景：标题" + 21L + "\n"
                        + "定位语：第2章 交通费下的金额标准表\n原文：高铁二等座可以报销。"));
        assertThat(updatedMiss.getContextualText()).isNull();
        assertThat(updatedMiss.getContextHash()).isEqualTo(HashUtil.sha256(
                "文档：差旅制度\n版本：id:9\n标题路径：未标注\n所属背景：标题" + 22L + "\n原文：打车需附行程单。"));
        // job：REINDEX + 全指纹 + CTX_LLM_V1 管线（幂等键含新 contextHash 与管线，中断重跑不重复）
        ArgumentCaptor<KnowledgeIndexJob> jobs = ArgumentCaptor.forClass(KnowledgeIndexJob.class);
        verify(jobMapper, times(2)).insertNodeJobIgnoreConflict(jobs.capture());
        assertThat(jobs.getAllValues()).allSatisfy(job -> {
            assertThat(job.getJobType()).isEqualTo("REINDEX");
            assertThat(job.getPipelineVersion()).isEqualTo("CTX_LLM_V1");
            assertThat(job.getEmbeddingModel()).isEqualTo("emb-m");
            assertThat(job.getParserVersion()).isEqualTo("5.2");
            assertThat(job.getChunkerVersion()).isEqualTo("1");
            assertThat(job.getIdempotencyKey()).isEqualTo(HashUtil.sha256(
                    job.getNodeId() + ":" + job.getContentHash() + ":" + job.getContextHash() + ":"
                            + job.getVersionId() + ":5.2:1:emb-m:CTX_LLM_V1:REINDEX"));
        });
        verify(jobMapper, never()).insertL1JobIgnoreConflict(any(KnowledgeIndexJob.class));
    }

    private static KnowledgeDocument doc(Long id, String docType) {
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setId(id); doc.setKbId(7L); doc.setDocType(docType); doc.setStatus("INDEXED");
        doc.setCurrentVersionId(9L); doc.setCreatedBy(42L);
        doc.setTitle("差旅制度");
        doc.setL1Metadata("{\"summary\":\"差旅制度摘要\"}");
        return doc;
    }

    private static KnowledgeNode node(Long id, String path, String metadata, String contextualText) {
        KnowledgeNode node = new KnowledgeNode();
        node.setId(id); node.setKbId(7L); node.setDocumentId(11L);
        node.setLevel("L2"); node.setNodeType("PARAGRAPH");
        node.setTitle("标题" + id); node.setPath(path);
        node.setMetadata(metadata);
        node.setContextualText(contextualText);
        node.setStatus("ACTIVE");
        return node;
    }
}
