package com.superprogrammer.chat.service.internal;

import com.superprogrammer.chat.entity.MemorySummaryCoverage;
import com.superprogrammer.chat.entity.MemoryTurn;
import com.superprogrammer.chat.mapper.MemorySummaryCoverageMapper;
import com.superprogrammer.chat.mapper.MemoryTurnMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 计划12 · D-5 · MemoryTurnPatcher 单测（Mockito，mock mapper）。
 * <p>
 * 二期 P1（V67）：turns 纯个人域——项目侧收集（ACL readableAuthors + findProjectRecallableTurns +
 * I3 离职过滤）随项目挂载列 DROP 整体下线，本类只测个人域。
 * <p>
 * 覆盖（对齐 §3.3 ⑥ allCovered 严格 + 防N+1 + L2边界）：
 * <ol>
 *   <li>空 scope / 个人关 → 返空，不调 mapper。</li>
 *   <li>turn 全 tag 覆盖 → 跳过（走 summary）。</li>
 *   <li>turn 部分 tag 覆盖 → 返（拼原文）。</li>
 *   <li>turn 无 tag → 返（allCovered false 保守不丢）。</li>
 *   <li>批量 coverage 一次查（防 N+1）。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class MemoryTurnPatcherTest {

    @Mock
    MemoryTurnMapper turnMapper;

    @Mock
    MemorySummaryCoverageMapper coverageMapper;

    private MemoryTurnPatcher patcher;

    @BeforeEach
    void setUp() {
        patcher = new MemoryTurnPatcher(turnMapper, coverageMapper);
    }

    private static MemoryTurn turn(long id, Long... tagIds) {
        MemoryTurn t = new MemoryTurn();
        t.setId(id);
        t.setUserId(1L);
        t.setTagIds(Arrays.asList(tagIds));
        t.setGenDone(true);
        t.setDirection("INPUT");
        return t;
    }

    private static MemorySummaryCoverage cov(long turnId, long tagId) {
        MemorySummaryCoverage c = new MemorySummaryCoverage();
        c.setTurnId(turnId);
        c.setTagId(tagId);
        c.setUserId(1L);
        return c;
    }

    private static RecallScope personalOnly() {
        return RecallScope.defaultPersonalOnly();
    }

    // ===== 空 scope =====

    @Test
    void emptyScope_returnsEmpty() {
        RecallScope empty = new RecallScope(false, List.of(), RecallDirection.BOTH, RecallTimeWindow.unbounded(), true);
        assertTrue(patcher.collectUncovered(empty, 1L).isEmpty());
        verifyNoInteractions(turnMapper);
    }

    @Test
    void personalOff_returnsEmpty() {
        // 项目 scope（个人关）：二期 P1 turns 纯个人域 → 空表，不查 turnMapper
        RecallScope projectOnly = new RecallScope(false, List.of(10L), RecallDirection.BOTH, RecallTimeWindow.unbounded(), true);
        assertTrue(patcher.collectUncovered(projectOnly, 1L).isEmpty());
        verifyNoInteractions(turnMapper);
    }

    // ===== allCovered 严格 =====

    @Test
    void allTagsCovered_skipTurn() {
        when(turnMapper.findPersonalRecallableTurns(anyLong(), anyString(), any(), any(), any()))
                .thenReturn(List.of(turn(1, 10L, 11L)));
        when(coverageMapper.findByUserAndTurns(eq(1L), anyList()))
                .thenReturn(List.of(cov(1, 10), cov(1, 11)));  // 全覆盖
        assertTrue(patcher.collectUncovered(personalOnly(), 1L).isEmpty());
    }

    @Test
    void partialCovered_returnTurn() {
        when(turnMapper.findPersonalRecallableTurns(anyLong(), anyString(), any(), any(), any()))
                .thenReturn(List.of(turn(1, 10L, 11L)));
        when(coverageMapper.findByUserAndTurns(eq(1L), anyList()))
                .thenReturn(List.of(cov(1, 10)));  // tag 11 未覆盖
        List<MemoryTurn> r = patcher.collectUncovered(personalOnly(), 1L);
        assertEquals(1, r.size());
        assertEquals(1L, r.get(0).getId());
    }

    @Test
    void noTags_returnTurn() {
        when(turnMapper.findPersonalRecallableTurns(anyLong(), anyString(), any(), any(), any()))
                .thenReturn(List.of(turn(1)));  // 无 tag
        when(coverageMapper.findByUserAndTurns(eq(1L), anyList())).thenReturn(List.of());
        List<MemoryTurn> r = patcher.collectUncovered(personalOnly(), 1L);
        assertEquals(1, r.size());  // 保守拼原文不丢
    }

    // ===== 防N+1 =====

    @Test
    void coverage_batchSingleQuery() {
        when(turnMapper.findPersonalRecallableTurns(anyLong(), anyString(), any(), any(), any()))
                .thenReturn(List.of(turn(1, 10L), turn(2, 20L)));
        when(coverageMapper.findByUserAndTurns(eq(1L), anyList())).thenReturn(List.of());
        patcher.collectUncovered(personalOnly(), 1L);
        // 一次 IN 查询，不逐 turn 查（防 N+1）
        verify(coverageMapper, times(1)).findByUserAndTurns(eq(1L), eq(List.of(1L, 2L)));
    }
}
