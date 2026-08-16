package com.superprogrammer.chat.service.internal;

import com.superprogrammer.chat.entity.MemoryConflict;
import com.superprogrammer.chat.entity.MemorySummary;
import com.superprogrammer.chat.entity.MemoryTurn;
import com.superprogrammer.chat.mapper.MemoryConflictMapper;
import com.superprogrammer.chat.mapper.MemorySummaryCoverageMapper;
import com.superprogrammer.chat.mapper.MemorySummaryMapper;
import com.superprogrammer.chat.service.internal.MemoryConflictJudge.SummaryConflictResult;
import com.superprogrammer.chat.service.internal.MemoryConsolidationCompressor.CompressedSummary;
import com.superprogrammer.chat.service.internal.MemoryConsolidationService.SummarizeResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 计划12 · E-3 · MemoryConsolidationTxService 单测（冲突检测 + 原子写）。
 * 验：无已有→CLEAN / 互斥→PENDING+markExisting+插冲突行 / 并存→CLEAN / 空 uncovered 不写 coverage。
 */
@ExtendWith(MockitoExtension.class)
class MemoryConsolidationTxServiceTest {

    @Mock MemorySummaryMapper summaryMapper;
    @Mock MemorySummaryCoverageMapper coverageMapper;
    @Mock MemoryConflictMapper conflictMapper;
    @Mock MemoryConflictJudge conflictJudge;
    @Mock com.superprogrammer.system.service.SystemSettingService systemSettingService;

    @InjectMocks MemoryConsolidationTxService txService;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        // 冲突判定 model 回退读可配默认（无源 turn chat_model 时）
        org.mockito.Mockito.lenient().when(systemSettingService.getMemoryJudgeModel())
                .thenReturn("doubao-seed-2.0-code");
    }

    private static MemoryTurn turn(long id) {
        MemoryTurn t = new MemoryTurn();
        t.setId(id);
        return t;
    }

    private static MemorySummary clean(long id) {
        MemorySummary s = new MemorySummary();
        s.setId(id);
        s.setStatus("CLEAN");
        s.setL1Summary("旧总结");
        return s;
    }

    private static CompressedSummary cs() {
        return new CompressedSummary("新L1", "新L2", List.of(101L));
    }

    // ---- 1. 无已有 CLEAN → 写 CLEAN + coverage，无冲突 ----

    @Test
    void noExistingWritesCleanNoConflict() {
        when(summaryMapper.findCleanByUserTagScope(eq(1L), eq(10L), any(), any())).thenReturn(List.of());
        when(summaryMapper.insert(any())).thenAnswer(inv -> {
            ((MemorySummary) inv.getArgument(0)).setId(500L);
            return 1;
        });

        SummarizeResult result = new SummarizeResult();
        txService.writeSummaryAndCoverage(1L, null, 10L, "工作", "BOTH", List.of(turn(101L)), cs(), result);

        ArgumentCaptor<MemorySummary> sc = ArgumentCaptor.forClass(MemorySummary.class);
        verify(summaryMapper).insert(sc.capture());
        assertEquals("CLEAN", sc.getValue().getStatus(), "无已有 → CLEAN");
        verify(coverageMapper).batchInsert(any());  // 1 条 coverage
        verify(conflictMapper, never()).insert(any());
        assertEquals(1, result.summariesWritten);
        assertEquals(0, result.conflictsCreated);
    }

    // ---- 2. 已有 + judge 互斥 → PENDING + markExisting + 插冲突行 ----

    @Test
    void conflictMarksPendingAndInsertsConflictRow() {
        MemorySummary existing = clean(200L);
        when(summaryMapper.findCleanByUserTagScope(eq(1L), eq(10L), any(), any())).thenReturn(List.of(existing));
        when(conflictJudge.judgeSummaryConflict(any(), any(), any(), any()))
                .thenReturn(new SummaryConflictResult(true, "新旧冲突，保留哪条？"));
        when(summaryMapper.insert(any())).thenAnswer(inv -> {
            ((MemorySummary) inv.getArgument(0)).setId(500L);
            return 1;
        });

        SummarizeResult result = new SummarizeResult();
        txService.writeSummaryAndCoverage(1L, null, 10L, "工作", "BOTH", List.of(turn(101L)), cs(), result);

        ArgumentCaptor<MemorySummary> sc = ArgumentCaptor.forClass(MemorySummary.class);
        verify(summaryMapper).insert(sc.capture());
        assertEquals("PENDING_CONFLICT", sc.getValue().getStatus(), "互斥 → 新 summary PENDING");
        verify(summaryMapper).markStatus(eq(200L), eq("PENDING_CONFLICT"));  // 已有也 PENDING

        ArgumentCaptor<MemoryConflict> cc = ArgumentCaptor.forClass(MemoryConflict.class);
        verify(conflictMapper).insert(cc.capture());
        assertEquals(10L, cc.getValue().getTagId(), "冲突行带 tag_id");
        assertEquals(500L, cc.getValue().getSummaryId(), "冲突行 summary_id=新");
        assertEquals("PENDING", cc.getValue().getStatus());
        assertEquals(1, result.conflictsCreated);
    }

    // ---- 3. 已有 + judge 并存 → 写 CLEAN，不 markExisting 不插冲突 ----

    @Test
    void coexistWritesCleanNoConflictRow() {
        when(summaryMapper.findCleanByUserTagScope(eq(1L), eq(10L), any(), any())).thenReturn(List.of(clean(200L)));
        when(conflictJudge.judgeSummaryConflict(any(), any(), any(), any()))
                .thenReturn(new SummaryConflictResult(false, null));
        when(summaryMapper.insert(any())).thenAnswer(inv -> {
            ((MemorySummary) inv.getArgument(0)).setId(500L);
            return 1;
        });

        SummarizeResult result = new SummarizeResult();
        txService.writeSummaryAndCoverage(1L, null, 10L, "工作", "BOTH", List.of(turn(101L)), cs(), result);

        ArgumentCaptor<MemorySummary> sc = ArgumentCaptor.forClass(MemorySummary.class);
        verify(summaryMapper).insert(sc.capture());
        assertEquals("CLEAN", sc.getValue().getStatus(), "并存 → CLEAN");
        verify(summaryMapper, never()).markStatus(anyLong(), any());
        verify(conflictMapper, never()).insert(any());
        assertEquals(0, result.conflictsCreated);
    }

    // ---- 4. uncovered 空 → 不写 coverage（但仍写 summary）----

    @Test
    void emptyUncoveredSkipsCoverageBatch() {
        when(summaryMapper.findCleanByUserTagScope(any(), any(), any(), any())).thenReturn(List.of());
        when(summaryMapper.insert(any())).thenAnswer(inv -> {
            ((MemorySummary) inv.getArgument(0)).setId(500L);
            return 1;
        });

        SummarizeResult result = new SummarizeResult();
        txService.writeSummaryAndCoverage(1L, null, 10L, "工作", "BOTH", List.of(), cs(), result);

        verify(coverageMapper, never()).batchInsert(any());
        assertEquals(1, result.summariesWritten);
    }
}
