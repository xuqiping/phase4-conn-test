package com.superprogrammer.knowledge.service;

import com.superprogrammer.knowledge.config.ReconciliationProperties;
import com.superprogrammer.knowledge.entity.KnowledgeReconciliationReport;
import com.superprogrammer.knowledge.mapper.KnowledgeBaseMapper;
import com.superprogrammer.knowledge.service.internal.ReconciliationTxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ReconciliationWorker poll 流程测（同步 executor 内联 scanBatch）。
 * enabled gate / KB 扫描分批 / orphan 触发清理 / decay 全局清 / 异常吞。
 */
@ExtendWith(MockitoExtension.class)
class ReconciliationWorkerTest {

    @Mock private ReconciliationTxService txService;
    @Mock private KnowledgeBaseMapper kbMapper;
    private final ReconciliationProperties props = new ReconciliationProperties();
    private final Executor directExecutor = Runnable::run;

    private ReconciliationWorker worker;

    @BeforeEach
    void setUp() {
        worker = new ReconciliationWorker(txService, kbMapper, props, directExecutor);
    }

    @Test
    void poll_disabled_isNoOp() {
        props.setEnabled(false);
        worker.poll();
        verifyNoInteractions(kbMapper, txService);
    }

    @Test
    void poll_enabled_scansKbsAndPurgesDecay() {
        props.setEnabled(true);
        props.setKbBatch(20);
        when(kbMapper.listActiveKbIds(eq(20), anyInt())).thenReturn(List.of(1L, 2L));
        when(txService.scanKb(anyLong())).thenReturn(report(0));   // orphan=0
        when(txService.purgeDecayedAnswerCache(anyInt(), anyInt())).thenReturn(5);

        worker.poll();

        verify(txService).scanKb(1L);
        verify(txService).scanKb(2L);
        verify(txService, never()).purgeOrphanEmbeddings(anyLong());   // orphan=0 不清
        verify(txService).purgeDecayedAnswerCache(props.getDecayBatch(), 100);
        verify(txService).purgeDecayedMemoryFacts(props.getDecayBatch(), 100);
    }

    @Test
    void poll_orphanPresent_triggersPurge() {
        props.setEnabled(true);
        when(kbMapper.listActiveKbIds(anyInt(), anyInt())).thenReturn(List.of(1L));
        when(txService.scanKb(1L)).thenReturn(report(3));   // orphan=3
        when(txService.purgeDecayedAnswerCache(anyInt(), anyInt())).thenReturn(0);
        when(txService.purgeOrphanEmbeddings(1L)).thenReturn(3);

        worker.poll();

        verify(txService).purgeOrphanEmbeddings(1L);
    }

    @Test
    void poll_noKbs_stillPurgesDecay() {
        props.setEnabled(true);
        when(kbMapper.listActiveKbIds(anyInt(), anyInt())).thenReturn(List.of());
        when(txService.purgeDecayedAnswerCache(anyInt(), anyInt())).thenReturn(0);

        worker.poll();

        verify(txService, never()).scanKb(anyLong());
        verify(txService).purgeDecayedAnswerCache(anyInt(), anyInt());   // decay 清理不依赖 KB
        verify(txService).purgeDecayedMemoryFacts(anyInt(), anyInt());   // memory_facts decay 同不依赖 KB
    }

    @Test
    void poll_scanException_swallowedDoesNotCrash() {
        props.setEnabled(true);
        when(kbMapper.listActiveKbIds(anyInt(), anyInt())).thenThrow(new RuntimeException("db down"));

        assertDoesNotThrow(() -> worker.poll());   // scheduler 不崩
    }

    @Test
    void poll_autoRepairEnqueued_driftTriggersRepair() {
        props.setEnabled(true);
        props.setAutoRepair(true);
        when(kbMapper.listActiveKbIds(anyInt(), anyInt())).thenReturn(List.of(1L));
        when(txService.scanKb(1L)).thenReturn(reportDrift(3));   // drift=3
        when(txService.purgeDecayedAnswerCache(anyInt(), anyInt())).thenReturn(0);
        when(txService.repairDrift(1L)).thenReturn(3);

        worker.poll();

        verify(txService).repairDrift(1L);   // autoRepair=true + drift>0 → 入 REINDEX
    }

    @Test
    void poll_autoRepairOff_noRepairCall() {
        props.setEnabled(true);
        // autoRepair 默认 false
        when(kbMapper.listActiveKbIds(anyInt(), anyInt())).thenReturn(List.of(1L));
        when(txService.scanKb(1L)).thenReturn(reportDrift(3));   // drift=3 但 autoRepair 关
        when(txService.purgeDecayedAnswerCache(anyInt(), anyInt())).thenReturn(0);

        worker.poll();

        verify(txService, never()).repairDrift(anyLong());   // 仅报告不入队
    }

    private KnowledgeReconciliationReport report(int orphan) {
        KnowledgeReconciliationReport r = new KnowledgeReconciliationReport();
        r.setKbId(1L);
        r.setOrphanCount(orphan);
        return r;
    }

    private KnowledgeReconciliationReport reportDrift(int drift) {
        KnowledgeReconciliationReport r = report(0);
        r.setDriftCount(drift);
        return r;
    }
}
