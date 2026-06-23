package com.superprogrammer.knowledge.service.internal;

import com.superprogrammer.knowledge.entity.KnowledgeNode;
import com.superprogrammer.knowledge.entity.KnowledgeReconciliationReport;
import com.superprogrammer.knowledge.mapper.KnowledgeEmbeddingMapper;
import com.superprogrammer.knowledge.mapper.KnowledgeIndexJobMapper;
import com.superprogrammer.knowledge.mapper.KnowledgeNodeMapper;
import com.superprogrammer.knowledge.mapper.KnowledgeReconciliationReportMapper;
import com.superprogrammer.knowledge.mapper.RagAnswerCacheMapper;
import com.superprogrammer.knowledge.mapper.RagMemoryFactMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ReconciliationTxService 计数/清理逻辑测（mock 全 mapper）。
 * scanKb 聚合计数 + purgeDecayedAnswerCache 批次循环 + purgeOrphanEmbeddings。
 */
@ExtendWith(MockitoExtension.class)
class ReconciliationTxServiceTest {

    @Mock private KnowledgeNodeMapper nodeMapper;
    @Mock private KnowledgeIndexJobMapper indexJobMapper;
    @Mock private KnowledgeEmbeddingMapper embeddingMapper;
    @Mock private RagAnswerCacheMapper answerCacheMapper;
    @Mock private RagMemoryFactMapper memoryFactMapper;
    @Mock private KnowledgeReconciliationReportMapper reportMapper;

    private ReconciliationTxService service;

    @BeforeEach
    void setUp() {
        service = new ReconciliationTxService(nodeMapper, indexJobMapper, embeddingMapper,
                answerCacheMapper, memoryFactMapper, reportMapper);
    }

    @Test
    void scanKb_aggregatesCountsAndInsertsReport() {
        when(nodeMapper.countActiveByKb(1L)).thenReturn(42L);
        when(indexJobMapper.findDriftedNodeIds(1L)).thenReturn(List.of(10L, 11L));   // drift 2
        when(indexJobMapper.countOrphanEmbeddings(1L)).thenReturn(3L);
        when(indexJobMapper.countDeadFailedByKb(1L)).thenReturn(1L);
        when(indexJobMapper.countStuckRunningByKb(1L)).thenReturn(2L);
        when(reportMapper.insert(any())).thenReturn(1);

        service.scanKb(1L);

        ArgumentCaptor<KnowledgeReconciliationReport> c = ArgumentCaptor.forClass(KnowledgeReconciliationReport.class);
        verify(reportMapper).insert(c.capture());
        KnowledgeReconciliationReport r = c.getValue();
        assertEquals(1L, r.getKbId());
        assertEquals(42, r.getTotalNodes());
        assertEquals(2, r.getDriftCount());
        assertEquals(3, r.getOrphanCount());
        assertEquals(3, r.getDeadJobCount());   // dead(1) + stuck(2)
        assertEquals(0, r.getRepairedCount());
        assertNotNull(r.getScannedAt());
    }

    @Test
    void scanKb_nullCountsTreatedAsZero() {
        when(nodeMapper.countActiveByKb(1L)).thenReturn(null);
        when(indexJobMapper.findDriftedNodeIds(1L)).thenReturn(List.of());
        when(indexJobMapper.countOrphanEmbeddings(1L)).thenReturn(null);
        when(indexJobMapper.countDeadFailedByKb(1L)).thenReturn(null);
        when(indexJobMapper.countStuckRunningByKb(1L)).thenReturn(null);
        when(reportMapper.insert(any())).thenReturn(1);

        service.scanKb(1L);

        ArgumentCaptor<KnowledgeReconciliationReport> c = ArgumentCaptor.forClass(KnowledgeReconciliationReport.class);
        verify(reportMapper).insert(c.capture());
        assertEquals(0, c.getValue().getTotalNodes());
        assertEquals(0, c.getValue().getOrphanCount());
        assertEquals(0, c.getValue().getDeadJobCount());
    }

    @Test
    void purgeDecayedAnswerCache_loopsUntilEmpty() {
        // 首批满 500，次批满 500，末批 100（< batchSize → 停）
        when(answerCacheMapper.deleteDecayed(500)).thenReturn(500, 500, 100);
        assertEquals(1100, service.purgeDecayedAnswerCache(500, 100));
        verify(answerCacheMapper, times(3)).deleteDecayed(500);
    }

    @Test
    void purgeDecayedAnswerCache_nothingToDelete() {
        when(answerCacheMapper.deleteDecayed(anyInt())).thenReturn(0);
        assertEquals(0, service.purgeDecayedAnswerCache(500, 100));
        verify(answerCacheMapper, times(1)).deleteDecayed(500);
    }

    @Test
    void purgeDecayedAnswerCache_respectsMaxBatches() {
        // 一直返满 500，达 maxBatches=2 → 停（防无限循环）
        when(answerCacheMapper.deleteDecayed(500)).thenReturn(500);
        assertEquals(1000, service.purgeDecayedAnswerCache(500, 2));
        verify(answerCacheMapper, times(2)).deleteDecayed(500);
    }

    @Test
    void purgeDecayedMemoryFacts_loopsUntilEmpty() {
        // 镜像 purgeDecayedAnswerCache：首批满 500，次批满 500，末批 100（< batchSize → 停）
        when(memoryFactMapper.deleteDecayed(500)).thenReturn(500, 500, 100);
        assertEquals(1100, service.purgeDecayedMemoryFacts(500, 100));
        verify(memoryFactMapper, times(3)).deleteDecayed(500);
    }

    @Test
    void purgeDecayedMemoryFacts_nothingToDelete() {
        // M2 软提示特性未启用 → 该表通常无行，返回 0
        when(memoryFactMapper.deleteDecayed(anyInt())).thenReturn(0);
        assertEquals(0, service.purgeDecayedMemoryFacts(500, 100));
        verify(memoryFactMapper, times(1)).deleteDecayed(500);
    }

    @Test
    void purgeDecayedMemoryFacts_respectsMaxBatches() {
        when(memoryFactMapper.deleteDecayed(500)).thenReturn(500);
        assertEquals(1000, service.purgeDecayedMemoryFacts(500, 2));
        verify(memoryFactMapper, times(2)).deleteDecayed(500);
    }

    @Test
    void purgeOrphanEmbeddings_returnsDeleted() {
        when(embeddingMapper.deleteOrphansByKb(1L)).thenReturn(3);
        assertEquals(3, service.purgeOrphanEmbeddings(1L));
    }

    @Test
    void enqueueReindexJobs_insertsForActiveNodes() {
        when(nodeMapper.selectById(1L)).thenReturn(node(1L, "h1", "ACTIVE"));
        when(nodeMapper.selectById(2L)).thenReturn(node(2L, "h2", "ACTIVE"));
        when(indexJobMapper.insertReindexJobIgnoreConflict(any())).thenReturn(1);

        assertEquals(2, service.enqueueReindexJobs(List.of(1L, 2L), 1L));
        verify(indexJobMapper, times(2)).insertReindexJobIgnoreConflict(any());
    }

    @Test
    void enqueueReindexJobs_skipsNullAndInactiveNodes() {
        when(nodeMapper.selectById(1L)).thenReturn(null);                 // 失活 → 跳
        when(nodeMapper.selectById(2L)).thenReturn(node(2L, "h2", "ARCHIVED"));  // 非 ACTIVE → 跳

        assertEquals(0, service.enqueueReindexJobs(List.of(1L, 2L), 1L));
        verify(indexJobMapper, never()).insertReindexJobIgnoreConflict(any());
    }

    @Test
    void enqueueReindexJobs_idempotentConflictCountsZero() {
        when(nodeMapper.selectById(1L)).thenReturn(node(1L, "h1", "ACTIVE"));
        when(indexJobMapper.insertReindexJobIgnoreConflict(any())).thenReturn(0);  // ON CONFLICT 跳过

        assertEquals(0, service.enqueueReindexJobs(List.of(1L), 1L));   // 已存在 job 不重复计入
    }

    @Test
    void enqueueReindexJobs_emptyListNoOp() {
        assertEquals(0, service.enqueueReindexJobs(List.of(), 1L));
        verifyNoInteractions(indexJobMapper);
    }

    @Test
    void repairDrift_delegatesToFindAndEnqueue() {
        when(indexJobMapper.findDriftedNodeIds(1L)).thenReturn(List.of(5L));
        when(nodeMapper.selectById(5L)).thenReturn(node(5L, "h5", "ACTIVE"));
        when(indexJobMapper.insertReindexJobIgnoreConflict(any())).thenReturn(1);

        assertEquals(1, service.repairDrift(1L));
        verify(indexJobMapper).findDriftedNodeIds(1L);
        verify(indexJobMapper).insertReindexJobIgnoreConflict(any());
    }

    @Test
    void repairDrift_noDriftReturnsZero() {
        when(indexJobMapper.findDriftedNodeIds(1L)).thenReturn(List.of());
        assertEquals(0, service.repairDrift(1L));
        verify(nodeMapper, never()).selectById(anyLong());
    }

    private KnowledgeNode node(long id, String hash, String status) {
        KnowledgeNode n = new KnowledgeNode();
        n.setId(id);
        n.setContentHash(hash);
        n.setStatus(status);
        return n;
    }
}
