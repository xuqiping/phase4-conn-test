package com.superprogrammer.knowledge.service;

import com.superprogrammer.knowledge.entity.KnowledgeBase;
import com.superprogrammer.knowledge.entity.KnowledgeIndexJob;
import com.superprogrammer.knowledge.entity.KnowledgeNode;
import com.superprogrammer.knowledge.mapper.KnowledgeNodeMapper;
import com.superprogrammer.knowledge.service.internal.IndexJobTxService;
import com.superprogrammer.knowledge.util.HalfVecUtil;
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
    @Mock private KnowledgeBaseService knowledgeBaseService;
    @Mock private LlmGateway llmGateway;

    /** 同步 executor：poll 提交的 process 立即在同线程跑。 */
    private final Executor directExecutor = Runnable::run;

    private IndexJobWorker worker;

    @BeforeEach
    void setUp() {
        worker = new IndexJobWorker(txService, nodeMapper, knowledgeBaseService, llmGateway, directExecutor);
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
        verify(llmGateway, never()).embed(anyString(), anyString());
        verify(txService, never()).completeUpsert(anyLong(), anyLong(), anyLong(), anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    void i2_nodeInactive_voidsJob() {
        KnowledgeIndexJob job = job(10L, 1L, "hash1");
        when(txService.claimBatch(anyInt())).thenReturn(List.of(job));
        when(nodeMapper.selectById(10L)).thenReturn(node("hash1", "ARCHIVED"));

        worker.poll();

        verify(txService).voidJob(eq(1L), anyString());
        verify(llmGateway, never()).embed(anyString(), anyString());
    }

    @Test
    void i2_hashMismatch_voidsJob() {
        KnowledgeIndexJob job = job(10L, 1L, "new-hash");
        when(txService.claimBatch(anyInt())).thenReturn(List.of(job));
        when(nodeMapper.selectById(10L)).thenReturn(node("old-hash", "ACTIVE"));  // node hash ≠ job hash

        worker.poll();

        verify(txService).voidJob(eq(1L), anyString());
        verify(llmGateway, never()).embed(anyString(), anyString());
    }

    @Test
    void success_completesUpsert() {
        KnowledgeIndexJob job = job(10L, 1L, "hash1");
        job.setKbId(7L);
        when(txService.claimBatch(anyInt())).thenReturn(List.of(job));
        KnowledgeNode n = node("hash1", "ACTIVE");
        n.setId(10L);
        n.setDocumentId(99L);
        when(nodeMapper.selectById(10L)).thenReturn(n);
        KnowledgeBase kb = new KnowledgeBase();
        kb.setEmbeddingModel("doubao-embedding-vision");
        when(knowledgeBaseService.ensure(7L)).thenReturn(kb);
        when(llmGateway.embed(anyString(), eq("doubao-embedding-vision"))).thenReturn(new float[HalfVecUtil.DIM]);

        worker.poll();

        verify(txService).completeUpsert(eq(1L), eq(10L), eq(99L), eq(7L),
                eq("doubao-embedding-vision"), anyString(), eq("hash1"));
        verify(txService, never()).failJob(anyLong(), anyString());
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
        when(llmGateway.embed(anyString(), anyString())).thenReturn(new float[1024]);  // 错维度

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
        when(llmGateway.embed(anyString(), anyString())).thenThrow(new RuntimeException("401 no key"));

        worker.poll();

        verify(txService).failJob(eq(1L), contains("401 no key"));
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
