package com.superprogrammer.chat.service.internal;

import com.superprogrammer.chat.entity.MemorySummaryCoverage;
import com.superprogrammer.chat.entity.MemoryTurn;
import com.superprogrammer.chat.mapper.MemorySummaryCoverageMapper;
import com.superprogrammer.chat.mapper.MemoryTurnMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 计划12 · D-5 · MemoryTurnPatcher 单测（Mockito，mock mapper + aclResolver）。
 * <p>
 * 覆盖（对齐 §3.3 ⑥ allCovered 严格 + 防N+1 + 向量14 + L2边界）：
 * <ol>
 *   <li>空 scope → 返空。</li>
 *   <li>turn 全 tag 覆盖 → 跳过（走 summary）。</li>
 *   <li>turn 部分 tag 覆盖 → 返（拼原文）。</li>
 *   <li>turn 无 tag → 返（allCovered false 保守不丢）。</li>
 *   <li>项目 readableAuthors 空集 → skip（向量14）。</li>
 *   <li>批量 coverage 一次查（防 N+1）。</li>
 *   <li>个人 + 自挂项目同 turn → 去重。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class MemoryTurnPatcherTest {

    @Mock
    MemoryTurnMapper turnMapper;

    @Mock
    MemorySummaryCoverageMapper coverageMapper;

    @Mock
    MemoryRecallAclResolver aclResolver;

    @Mock
    MemoryDepartedResolver departedResolver;

    private MemoryTurnPatcher patcher;

    @BeforeEach
    void setUp() {
        patcher = new MemoryTurnPatcher(turnMapper, coverageMapper, aclResolver, departedResolver);
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

    private static RecallScope projectOnly(Long... pids) {
        return new RecallScope(false, List.of(pids), RecallDirection.BOTH, RecallTimeWindow.unbounded(), true);
    }

    // ===== 空 scope =====

    @Test
    void emptyScope_returnsEmpty() {
        RecallScope empty = new RecallScope(false, List.of(), RecallDirection.BOTH, RecallTimeWindow.unbounded(), true);
        assertTrue(patcher.collectUncovered(empty, 1L).isEmpty());
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

    // ===== 项目 ACL =====

    @Test
    void project_authorsEmpty_skip() {
        when(aclResolver.readableAuthors(10L, 1L)).thenReturn(Set.of());
        assertTrue(patcher.collectUncovered(projectOnly(10L), 1L).isEmpty());
        verify(turnMapper, never()).findProjectRecallableTurns(anyLong(), anyLong(), anyList(), anyString(), any(), any(), any());
    }

    @Test
    void project_authorsNonEmpty_queriedAndFiltered() {
        when(aclResolver.readableAuthors(10L, 1L)).thenReturn(Set.of(2L));
        when(turnMapper.findProjectRecallableTurns(eq(10L), eq(1L), anyList(), anyString(), any(), any(), any()))
                .thenReturn(List.of(turn(3, 20L)));
        when(coverageMapper.findByUserAndTurns(eq(1L), anyList())).thenReturn(List.of());  // 无覆盖 → 拼原文
        List<MemoryTurn> r = patcher.collectUncovered(projectOnly(10L), 1L);
        assertEquals(1, r.size());
        assertEquals(3L, r.get(0).getId());
    }

    // ===== 防N+1 + 去重 =====

    @Test
    void coverage_batchSingleQuery() {
        when(turnMapper.findPersonalRecallableTurns(anyLong(), anyString(), any(), any(), any()))
                .thenReturn(List.of(turn(1, 10L), turn(2, 20L)));
        when(coverageMapper.findByUserAndTurns(eq(1L), anyList())).thenReturn(List.of());
        patcher.collectUncovered(personalOnly(), 1L);
        // 一次 IN 查询，不逐 turn 查（防 N+1）
        verify(coverageMapper, times(1)).findByUserAndTurns(eq(1L), eq(List.of(1L, 2L)));
    }

    @Test
    void personalAndProject_sameTurn_deduped() {
        when(turnMapper.findPersonalRecallableTurns(anyLong(), anyString(), any(), any(), any()))
                .thenReturn(List.of(turn(1, 10L)));
        when(aclResolver.readableAuthors(10L, 1L)).thenReturn(Set.of(1L));
        when(turnMapper.findProjectRecallableTurns(eq(10L), eq(1L), anyList(), anyString(), any(), any(), any()))
                .thenReturn(List.of(turn(1, 10L)));  // 同 turn 重复
        when(coverageMapper.findByUserAndTurns(eq(1L), anyList())).thenReturn(List.of(cov(1, 10)));
        // 去重后 1 turn，全覆盖 → 空结果
        assertTrue(patcher.collectUncovered(
                new RecallScope(true, List.of(10L), RecallDirection.BOTH, RecallTimeWindow.unbounded(), true), 1L).isEmpty());
        verify(coverageMapper).findByUserAndTurns(eq(1L), eq(List.of(1L)));  // 去重后只 1 个 turnId
    }

    // ===== I3 离职开关（L10，§3.7 line158）=====

    @Test
    void project_includeDepartedFalse_剔DEPARTED作者() {
        // scope.includeDeparted=false，readableAuthors={2,3}，DEPARTED={3} → 传 turnMapper 的 authors={2}
        when(aclResolver.readableAuthors(10L, 1L)).thenReturn(Set.of(2L, 3L));
        when(departedResolver.resolveDeparted(10L)).thenReturn(
                new MemoryDepartedResolver.DepartedInfo(Set.of(3L), Map.of(3L, "已离开人员·u3·2026-01-01")));
        when(turnMapper.findProjectRecallableTurns(eq(10L), eq(1L), anyList(), anyString(), any(), any(), any()))
                .thenReturn(List.of());
        RecallScope scope = new RecallScope(false, List.of(10L), RecallDirection.BOTH, RecallTimeWindow.unbounded(), false);

        patcher.collectUncovered(scope, 1L);

        ArgumentCaptor<List<Long>> captor = ArgumentCaptor.forClass(List.class);
        verify(turnMapper).findProjectRecallableTurns(eq(10L), eq(1L), captor.capture(), anyString(), any(), any(), any());
        assertEquals(List.of(2L), captor.getValue(), "剔 DEPARTED 3 → 只传 2 给 mapper（优先级高于人员多选）");
    }

    @Test
    void project_includeDepartedFalse_全DEPARTED_skip() {
        // readableAuthors 全是 DEPARTED → 剔完空 → skip（不查 turnMapper）
        when(aclResolver.readableAuthors(10L, 1L)).thenReturn(Set.of(3L));
        when(departedResolver.resolveDeparted(10L)).thenReturn(
                new MemoryDepartedResolver.DepartedInfo(Set.of(3L), Map.of(3L, "x")));
        RecallScope scope = new RecallScope(false, List.of(10L), RecallDirection.BOTH, RecallTimeWindow.unbounded(), false);

        assertTrue(patcher.collectUncovered(scope, 1L).isEmpty());
        verify(turnMapper, never()).findProjectRecallableTurns(anyLong(), anyLong(), anyList(), anyString(), any(), any(), any());
    }

    @Test
    void project_includeDepartedTrue_不过滤不调DepartedResolver() {
        // includeDeparted=true → 保留 DEPARTED，不调 departedResolver（标注由 Pipeline 装配）
        when(aclResolver.readableAuthors(10L, 1L)).thenReturn(Set.of(2L, 3L));
        when(turnMapper.findProjectRecallableTurns(eq(10L), eq(1L), anyList(), anyString(), any(), any(), any()))
                .thenReturn(List.of());

        patcher.collectUncovered(projectOnly(10L), 1L);  // includeDeparted=true

        verify(departedResolver, never()).resolveDeparted(any());
    }
}
