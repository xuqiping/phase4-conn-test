package com.superprogrammer.knowledge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.knowledge.entity.KnowledgeBase;
import com.superprogrammer.knowledge.entity.KnowledgeDocument;
import com.superprogrammer.knowledge.entity.KnowledgeIndexJob;
import com.superprogrammer.knowledge.entity.KnowledgeNode;
import com.superprogrammer.knowledge.mapper.KnowledgeDocumentMapper;
import com.superprogrammer.knowledge.mapper.KnowledgeNodeMapper;
import com.superprogrammer.knowledge.service.internal.IndexJobTxService;
import com.superprogrammer.knowledge.service.internal.L1Metadata;
import com.superprogrammer.knowledge.util.HalfVecUtil;
import com.superprogrammer.knowledge.util.HashUtil;
import com.superprogrammer.knowledge.util.L1EmbedText;
import com.superprogrammer.file.service.FileStorageService;
import com.superprogrammer.llm.LlmGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.Executor;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * IndexJobWorker I2 re-check + embed 维度校验 + 失败路由测。
 * 注入同步 executor（Runnable::run）使 process() 在 poll() 内联执行，可断言 voidJob/completeUpsert/failJob。
 */
@ExtendWith(MockitoExtension.class)
class IndexJobWorkerTest {

    @Mock private IndexJobTxService txService;
    @Mock private KnowledgeNodeMapper nodeMapper;
    @Mock private KnowledgeDocumentMapper documentMapper;
    @Mock private KnowledgeBaseService knowledgeBaseService;
    @Mock private LlmGateway llmGateway;
    @Mock private FileStorageService fileStorageService;
    @Mock private com.superprogrammer.common.metrics.BizMetrics bizMetrics;
    @Mock private com.superprogrammer.knowledge.opensearch.OpenSearchChunkWriter openSearchChunkWriter;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 同步 executor：poll 提交的 process 立即在同线程跑。 */
    private final Executor directExecutor = Runnable::run;

    private IndexJobWorker worker;

    @BeforeEach
    void setUp() {
        // retainAfterIndex=false：completeUpsert 返回 fileRef 时触发清原件（此处 mock 返 null，不触发）
        worker = new IndexJobWorker(txService, nodeMapper, documentMapper, knowledgeBaseService,
                llmGateway, objectMapper, directExecutor, fileStorageService, false, bizMetrics,
                new Contextualizer(objectMapper));
    }

    // ===== OPS-FR-05 队列指标 =====

    @Test
    void gaugeRegistersAtStartup_andReadsCountClaimable() {
        // 启动即注册 queue.depth Gauge（防首次 scrape NaN），读数=txService.countClaimable
        io.micrometer.prometheus.PrometheusMeterRegistry registry =
                new io.micrometer.prometheus.PrometheusMeterRegistry(io.micrometer.prometheus.PrometheusConfig.DEFAULT);
        IndexJobWorker w = new IndexJobWorker(txService, nodeMapper, documentMapper, knowledgeBaseService,
                llmGateway, objectMapper, directExecutor, fileStorageService, false,
                new com.superprogrammer.common.metrics.BizMetrics(registry), new Contextualizer(objectMapper));
        when(txService.countClaimable()).thenReturn(37L);

        w.registerQueueDepthGauge();

        org.junit.jupiter.api.Assertions.assertTrue(
                registry.scrape().contains("knowledge_index_queue_depth 37.0"), registry.scrape());
    }

    @Test
    void i2_void_recordsVoidMetric() {
        KnowledgeIndexJob job = job(10L, 1L, "hash1");
        when(txService.claimBatch(anyInt())).thenReturn(List.of(job));
        when(nodeMapper.selectById(10L)).thenReturn(null);

        worker.poll();

        verify(bizMetrics).indexed(com.superprogrammer.common.metrics.BizMetrics.INDEX_VOID);
    }

    @Test
    void poll_emptyClaim_doesNothing() {
        when(txService.claimBatch(anyInt())).thenReturn(List.of());

        worker.poll();

        verify(nodeMapper, never()).selectById(anyLong());
        verify(txService, never()).voidJob(anyLong(), anyString());
    }

    @Test
    void i2_nodeNull_voidsJob() {
        KnowledgeIndexJob job = job(10L, 1L, "hash1");
        when(txService.claimBatch(anyInt())).thenReturn(List.of(job));
        when(nodeMapper.selectById(10L)).thenReturn(null);

        worker.poll();

        verify(txService).voidJob(eq(1L), contains("节点已变更"));
        verify(llmGateway, never()).embed(anyString(), anyString(), any());
        verify(txService, never()).completeUpsert(anyLong(), anyLong(), anyLong(), anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    void i2_nodeInactive_voidsJob() {
        KnowledgeIndexJob job = job(10L, 1L, "hash1");
        when(txService.claimBatch(anyInt())).thenReturn(List.of(job));
        when(nodeMapper.selectById(10L)).thenReturn(node("hash1", "ARCHIVED"));

        worker.poll();

        verify(txService).voidJob(eq(1L), anyString());
        verify(llmGateway, never()).embed(anyString(), anyString(), any());
    }

    @Test
    void i2_hashMismatch_voidsJob() {
        KnowledgeIndexJob job = job(10L, 1L, "new-hash");
        when(txService.claimBatch(anyInt())).thenReturn(List.of(job));
        when(nodeMapper.selectById(10L)).thenReturn(node("old-hash", "ACTIVE"));  // node hash ≠ job hash

        worker.poll();

        verify(txService).voidJob(eq(1L), anyString());
        verify(llmGateway, never()).embed(anyString(), anyString(), any());
    }

    @Test
    void success_completesUpsert() {
        KnowledgeIndexJob job = job(10L, 1L, "hash1");
        job.setKbId(7L);
        when(txService.claimBatch(anyInt())).thenReturn(List.of(job));
        KnowledgeNode n = node("hash1", "ACTIVE");
        n.setId(10L);
        n.setDocumentId(99L);
        n.setTitle("环境准备");
        n.setVersionId(3L);
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setId(99L);
        doc.setTitle("部署手册");
        when(documentMapper.selectById(99L)).thenReturn(doc);
        com.superprogrammer.knowledge.entity.KnowledgeDocumentVersion version =
                new com.superprogrammer.knowledge.entity.KnowledgeDocumentVersion();
        version.setId(3L);
        Contextualizer.ContextualContent contextual = new Contextualizer(objectMapper).contextualize(doc, version, n);
        n.setContextHash(contextual.contextHash());
        job.setContextHash(contextual.contextHash());
        job.setEmbeddingModel("task-embedding-model");
        when(nodeMapper.selectById(10L)).thenReturn(n);
        when(llmGateway.embed(eq(contextual.text()), eq("task-embedding-model"), any())).thenReturn(new float[HalfVecUtil.DIM]);

        worker.poll();

        verify(txService).completeUpsert(eq(1L), eq(10L), eq(99L), eq(7L),
                eq("task-embedding-model"), anyString(), eq("hash1"), eq(contextual.contextHash()));
        verify(txService, never()).failJob(anyLong(), anyString());
    }

    // WP3 C4：节点带定位语 → embed 文本含「定位语：」行且 hash 复校通过（CTX_LLM_V1 管线 worker 侧）
    @Test
    void contextualTextNode_embedsLocatorLineAndHashesMatch() {
        KnowledgeIndexJob job = job(10L, 1L, "hash1");
        job.setKbId(7L);
        when(txService.claimBatch(anyInt())).thenReturn(List.of(job));
        KnowledgeNode n = node("hash1", "ACTIVE");
        n.setId(10L);
        n.setDocumentId(99L);
        n.setTitle("环境准备");
        n.setVersionId(3L);
        n.setContextualText("第1章 环境准备中的硬件要求清单");   // 先设定位语再算 hash → node/job/实际三方一致
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setId(99L);
        doc.setTitle("部署手册");
        when(documentMapper.selectById(99L)).thenReturn(doc);
        com.superprogrammer.knowledge.entity.KnowledgeDocumentVersion version =
                new com.superprogrammer.knowledge.entity.KnowledgeDocumentVersion();
        version.setId(3L);
        Contextualizer.ContextualContent contextual = new Contextualizer(objectMapper).contextualize(doc, version, n);
        n.setContextHash(contextual.contextHash());
        job.setContextHash(contextual.contextHash());
        job.setEmbeddingModel("task-embedding-model");
        when(nodeMapper.selectById(10L)).thenReturn(n);
        org.mockito.ArgumentCaptor<String> embedText = org.mockito.ArgumentCaptor.forClass(String.class);
        when(llmGateway.embed(embedText.capture(), eq("task-embedding-model"), any()))
                .thenReturn(new float[HalfVecUtil.DIM]);

        worker.poll();

        org.junit.jupiter.api.Assertions.assertTrue(
                embedText.getValue().contains("\n定位语：第1章 环境准备中的硬件要求清单"), embedText.getValue());
        verify(txService).completeUpsert(eq(1L), eq(10L), eq(99L), eq(7L),
                eq("task-embedding-model"), anyString(), eq("hash1"), eq(contextual.contextHash()));
        verify(txService, never()).voidJob(anyLong(), anyString());
    }

    @Test
    void snapshotRebuildWritesIsolatedPhysicalIndexInsteadOfLiveAlias() {
        org.springframework.test.util.ReflectionTestUtils.setField(worker, "openSearchChunkWriter", openSearchChunkWriter);
        KnowledgeIndexJob job = job(10L, 1L, "hash1");
        job.setKbId(7L);
        job.setTargetSnapshotId("snap-1");
        job.setTargetPhysicalIndex("kb-7-chunks-snap-1-pipe-v2");
        job.setEmbeddingModel("task-embedding-model");
        when(txService.claimBatch(anyInt())).thenReturn(List.of(job));
        KnowledgeNode n = node("hash1", "ACTIVE");
        n.setId(10L);
        n.setDocumentId(99L);
        n.setVersionId(3L);
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setId(99L);
        doc.setTitle("部署手册");
        when(documentMapper.selectById(99L)).thenReturn(doc);
        com.superprogrammer.knowledge.entity.KnowledgeDocumentVersion version =
                new com.superprogrammer.knowledge.entity.KnowledgeDocumentVersion();
        version.setId(3L);
        Contextualizer.ContextualContent contextual = new Contextualizer(objectMapper).contextualize(doc, version, n);
        n.setContextHash(contextual.contextHash());
        job.setContextHash(contextual.contextHash());
        when(nodeMapper.selectById(10L)).thenReturn(n);
        when(llmGateway.embed(anyString(), eq("task-embedding-model"), any())).thenReturn(new float[HalfVecUtil.DIM]);

        worker.poll();

        verify(openSearchChunkWriter).write(eq("kb-7-chunks-snap-1-pipe-v2"), anyList());
        verify(openSearchChunkWriter, never()).write(eq("kb-7-chunks-write"), anyList());
    }

    @Test
    void contextualHashMismatchVoidsWithoutEmbedding() {
        KnowledgeIndexJob job = job(10L, 1L, "hash1");
        job.setKbId(7L);
        job.setContextHash("stale-context-hash");
        job.setEmbeddingModel("task-embedding-model");
        when(txService.claimBatch(anyInt())).thenReturn(List.of(job));
        KnowledgeNode n = node("hash1", "ACTIVE");
        n.setId(10L); n.setDocumentId(99L); n.setVersionId(3L);
        n.setContextHash("current-context-hash");
        when(nodeMapper.selectById(10L)).thenReturn(n);
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setId(99L); doc.setTitle("部署手册");
        when(documentMapper.selectById(99L)).thenReturn(doc);
        worker.poll();

        verify(txService).voidJob(eq(1L), contains("上下文化文本或版本已变更"));
        verify(llmGateway, never()).embed(anyString(), anyString(), any());
    }

    @Test
    void embedDimMismatch_failJob() {
        KnowledgeIndexJob job = job(10L, 1L, "hash1");
        job.setKbId(7L);
        when(txService.claimBatch(anyInt())).thenReturn(List.of(job));
        when(nodeMapper.selectById(10L)).thenReturn(node("hash1", "ACTIVE"));
        KnowledgeBase kb = new KnowledgeBase();
        kb.setEmbeddingModel("doubao-embedding-vision");
        when(knowledgeBaseService.ensure(7L)).thenReturn(kb);
        when(llmGateway.embed(anyString(), anyString(), any())).thenReturn(new float[1024]);  // 错维度

        worker.poll();

        verify(txService).failJob(eq(1L), contains("维度不匹配"));
        verify(txService, never()).completeUpsert(anyLong(), anyLong(), anyLong(), anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    void embedThrows_failJob() {
        KnowledgeIndexJob job = job(10L, 1L, "hash1");
        job.setKbId(7L);
        when(txService.claimBatch(anyInt())).thenReturn(List.of(job));
        when(nodeMapper.selectById(10L)).thenReturn(node("hash1", "ACTIVE"));
        KnowledgeBase kb = new KnowledgeBase();
        kb.setEmbeddingModel("doubao-embedding-vision");
        when(knowledgeBaseService.ensure(7L)).thenReturn(kb);
        when(llmGateway.embed(anyString(), anyString(), any())).thenThrow(new RuntimeException("401 no key"));

        worker.poll();

        verify(txService).failJob(eq(1L), contains("401 no key"));
    }

    // ============================ Phase3 UPSERT_L1 ============================

    @Test
    void upsertL1_success_completesL1() throws Exception {
        L1Metadata l1 = L1Metadata.builder()
                .summary("安装部署指南").outline(List.of("环境准备")).importantRules(List.of("备份")).build();
        String l1Hash = HashUtil.sha256(L1EmbedText.build(l1));
        KnowledgeIndexJob job = new KnowledgeIndexJob();
        job.setId(1L);
        job.setJobType("UPSERT_L1");
        job.setDocumentId(50L);
        job.setKbId(7L);
        job.setContentHash(l1Hash);
        when(txService.claimBatch(anyInt())).thenReturn(List.of(job));
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setId(50L);
        doc.setL1Metadata(objectMapper.writeValueAsString(l1));
        when(documentMapper.selectById(50L)).thenReturn(doc);
        KnowledgeBase kb = new KnowledgeBase();
        kb.setEmbeddingModel("doubao-embedding-vision");
        when(knowledgeBaseService.ensure(7L)).thenReturn(kb);
        when(llmGateway.embed(anyString(), eq("doubao-embedding-vision"), any())).thenReturn(new float[HalfVecUtil.DIM]);

        worker.poll();

        verify(txService).completeUpsertL1(eq(1L), eq(50L), eq(7L), eq("doubao-embedding-vision"), anyString(), eq(l1Hash));
        verify(txService, never()).voidJob(anyLong(), anyString());
    }

    @Test
    void upsertL1_docMissing_voids() {
        KnowledgeIndexJob job = new KnowledgeIndexJob();
        job.setId(1L);
        job.setJobType("UPSERT_L1");
        job.setDocumentId(50L);
        job.setContentHash("h");
        when(txService.claimBatch(anyInt())).thenReturn(List.of(job));
        when(documentMapper.selectById(50L)).thenReturn(null);

        worker.poll();

        verify(txService).voidJob(eq(1L), contains("无 L1 元数据"));
        verify(txService, never()).completeUpsertL1(anyLong(), anyLong(), anyLong(), anyString(), anyString(), anyString());
        verify(llmGateway, never()).embed(anyString(), anyString(), any());
    }

    @Test
    void upsertL1_hashMismatch_voids() throws Exception {
        L1Metadata l1 = L1Metadata.builder().summary("新摘要").build();
        KnowledgeIndexJob job = new KnowledgeIndexJob();
        job.setId(1L);
        job.setJobType("UPSERT_L1");
        job.setDocumentId(50L);
        job.setContentHash("stale-hash-not-matching");
        when(txService.claimBatch(anyInt())).thenReturn(List.of(job));
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setId(50L);
        doc.setL1Metadata(objectMapper.writeValueAsString(l1));
        when(documentMapper.selectById(50L)).thenReturn(doc);

        worker.poll();

        verify(txService).voidJob(eq(1L), contains("L1 元数据已变更"));
        verify(txService, never()).completeUpsertL1(anyLong(), anyLong(), anyLong(), anyString(), anyString(), anyString());
        verify(llmGateway, never()).embed(anyString(), anyString(), any());
    }

    private KnowledgeIndexJob job(Long nodeId, Long jobId, String contentHash) {
        KnowledgeIndexJob j = new KnowledgeIndexJob();
        j.setId(jobId);
        j.setNodeId(nodeId);
        j.setContentHash(contentHash);
        return j;
    }

    private KnowledgeNode node(String contentHash, String status) {
        KnowledgeNode n = new KnowledgeNode();
        n.setContentHash(contentHash);
        n.setStatus(status);
        n.setContent("L0 摘要内容");
        return n;
    }
}
