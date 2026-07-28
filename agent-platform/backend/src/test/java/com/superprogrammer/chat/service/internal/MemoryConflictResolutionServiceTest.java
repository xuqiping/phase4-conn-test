package com.superprogrammer.chat.service.internal;

import com.superprogrammer.chat.entity.MemoryConflict;
import com.superprogrammer.chat.entity.MemorySummary;
import com.superprogrammer.chat.mapper.MemoryConflictMapper;
import com.superprogrammer.chat.mapper.MemoryNotificationMapper;
import com.superprogrammer.chat.mapper.MemorySummaryCoverageMapper;
import com.superprogrammer.chat.mapper.MemorySummaryMapper;
import com.superprogrammer.chat.mapper.MemoryTurnMapper;
import com.superprogrammer.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 计划12 · E-4 · MemoryConflictResolutionService 单测（四选项 + DISCARD 级联 + 12h 拒）。
 */
@ExtendWith(MockitoExtension.class)
class MemoryConflictResolutionServiceTest {

    @Mock MemoryConflictMapper conflictMapper;
    @Mock MemorySummaryMapper summaryMapper;
    @Mock MemorySummaryCoverageMapper coverageMapper;
    @Mock MemoryTurnMapper turnMapper;
    @Mock MemoryNotificationMapper notificationMapper;
    @Mock MemoryQueryCache queryCache;

    @InjectMocks MemoryConflictResolutionService service;

    private static final Long UID = 1L;
    private static final Long OTHER = 2L;

    private static MemoryConflict pendingConflict(Long id, Long summaryId) {
        MemoryConflict c = new MemoryConflict();
        c.setId(id);
        c.setUserId(UID);
        c.setTagId(10L);
        c.setSummaryId(summaryId);
        c.setStatus("PENDING");
        return c;
    }

    private static MemorySummary summary(Long id, Long userId, String status, List<Long> sourceTurnIds, OffsetDateTime summarizedAt) {
        MemorySummary s = new MemorySummary();
        s.setId(id);
        s.setUserId(userId);
        s.setTagId(10L);
        s.setProjectId(null);
        s.setStatus(status);
        s.setSourceTurnIds(sourceTurnIds);
        s.setSummarizedAt(summarizedAt);
        return s;
    }

    // ---- 1. 不存在 / 非作者 / 非 V47 → NOT_FOUND ----

    @Test
    void resolveNotFoundCases() {
        // 不存在
        assertThrows(BusinessException.class, () -> service.resolve(UID, 1L, "KEEP_BOTH"));
        // 非作者
        MemoryConflict others = new MemoryConflict();
        others.setId(1L); others.setUserId(99L); others.setTagId(10L); others.setStatus("PENDING");
        when(conflictMapper.selectById(1L)).thenReturn(others);
        assertThrows(BusinessException.class, () -> service.resolve(UID, 1L, "KEEP_BOTH"));
        // 非 V47（tagId null，legacy）
        MemoryConflict legacy = new MemoryConflict();
        legacy.setId(2L); legacy.setUserId(UID); legacy.setTagId(null); legacy.setStatus("PENDING");
        when(conflictMapper.selectById(2L)).thenReturn(legacy);
        assertThrows(BusinessException.class, () -> service.resolve(UID, 2L, "KEEP_BOTH"));
    }

    // ---- 2. 非 PENDING → 幂等 false ----

    @Test
    void nonPendingReturnsFalse() {
        MemoryConflict c = pendingConflict(1L, 100L);
        c.setStatus("RESOLVED");
        when(conflictMapper.selectById(1L)).thenReturn(c);

        boolean r = service.resolve(UID, 1L, "KEEP_BOTH");

        assertFalse(r, "已裁决 → 幂等 false");
        verify(summaryMapper, never()).markStatus(anyLong(), any());
    }

    // ---- 3. 非法 decision → BAD_REQUEST ----

    @Test
    void invalidDecisionBadRequest() {
        when(conflictMapper.selectById(1L)).thenReturn(pendingConflict(1L, 100L));

        assertThrows(BusinessException.class, () -> service.resolve(UID, 1L, "DELETE_ALL"));
    }

    // ---- 4. KEEP_BOTH：两方 PENDING → CLEAN，无软删 ----

    @Test
    void keepBothMarksAllClean() {
        when(conflictMapper.selectById(1L)).thenReturn(pendingConflict(1L, 100L));
        when(summaryMapper.selectById(100L)).thenReturn(summary(100L, UID, "PENDING_CONFLICT", List.of(), OffsetDateTime.now()));
        when(summaryMapper.findByUserTagScopeStatus(eq(UID), eq(10L), any(), eq("PENDING_CONFLICT")))
                .thenReturn(List.of(summary(100L, UID, "PENDING_CONFLICT", List.of(), OffsetDateTime.now()),
                        summary(200L, UID, "PENDING_CONFLICT", List.of(), OffsetDateTime.now())));

        boolean r = service.resolve(UID, 1L, "KEEP_BOTH");

        assertTrue(r);
        verify(summaryMapper).markStatus(100L, "CLEAN");
        verify(summaryMapper).markStatus(200L, "CLEAN");
        verify(summaryMapper, never()).softDeleteByIds(any());
    }

    // ---- 5. KEEP_NEW：留 trigger，败方软删 + 清 coverage ----

    @Test
    void keepNewSoftDeletesOthers() {
        when(conflictMapper.selectById(1L)).thenReturn(pendingConflict(1L, 100L));
        when(summaryMapper.selectById(100L)).thenReturn(summary(100L, UID, "PENDING_CONFLICT", List.of(), OffsetDateTime.now()));
        when(summaryMapper.findByUserTagScopeStatus(eq(UID), eq(10L), any(), eq("PENDING_CONFLICT")))
                .thenReturn(List.of(summary(100L, UID, "PENDING_CONFLICT", List.of(), OffsetDateTime.now()),
                        summary(200L, UID, "PENDING_CONFLICT", List.of(), OffsetDateTime.now())));

        service.resolve(UID, 1L, "KEEP_NEW");

        verify(summaryMapper).softDeleteByIds(eq(List.of(200L)));
        verify(coverageMapper).deleteBySummaryId(200L);
        verify(summaryMapper).markStatus(100L, "CLEAN");
    }

    // ---- 6. KEEP_OLD：留 old，败方=trigger 软删 ----

    @Test
    void keepOldSoftDeletesTrigger() {
        when(conflictMapper.selectById(1L)).thenReturn(pendingConflict(1L, 100L));
        when(summaryMapper.selectById(100L)).thenReturn(summary(100L, UID, "PENDING_CONFLICT", List.of(), OffsetDateTime.now()));
        when(summaryMapper.findByUserTagScopeStatus(eq(UID), eq(10L), any(), eq("PENDING_CONFLICT")))
                .thenReturn(List.of(summary(100L, UID, "PENDING_CONFLICT", List.of(), OffsetDateTime.now()),
                        summary(200L, UID, "PENDING_CONFLICT", List.of(), OffsetDateTime.now())));

        service.resolve(UID, 1L, "KEEP_OLD");

        verify(summaryMapper).softDeleteByIds(eq(List.of(100L)));
        verify(coverageMapper).deleteBySummaryId(100L);
        verify(summaryMapper).markStatus(200L, "CLEAN");
    }

    // ---- 7. DISCARD 无他人引用：软删 summary + turns + coverage ----

    @Test
    void discardNoOtherRefsSoftDeletesSummaryAndTurns() {
        when(conflictMapper.selectById(1L)).thenReturn(pendingConflict(1L, 100L));
        when(summaryMapper.selectById(100L)).thenReturn(summary(100L, UID, "PENDING_CONFLICT", List.of(301L, 302L), OffsetDateTime.now()));
        when(summaryMapper.findSummariesReferencingTurn(301L)).thenReturn(List.of());
        when(summaryMapper.findSummariesReferencingTurn(302L)).thenReturn(List.of());

        service.resolve(UID, 1L, "DISCARD");

        verify(summaryMapper).softDeleteByIds(eq(List.of(100L)));
        verify(coverageMapper).deleteBySummaryId(100L);
        verify(turnMapper).softDeleteByIds(eq(List.of(301L, 302L)));
        verify(coverageMapper).deleteByTurnIdsAndUser(eq(List.of(301L, 302L)), eq(UID));
        verify(notificationMapper, never()).insert(any());
        verify(conflictMapper).markV47Resolved(1L, "DISCARD");
    }

    // ---- 8. DISCARD 他人引用 >12h → FORBIDDEN 拒（防波及他人稳定总结）----

    @Test
    void discardRejectsWhenOtherReferenceOlderThan12h() {
        when(conflictMapper.selectById(1L)).thenReturn(pendingConflict(1L, 100L));
        when(summaryMapper.selectById(100L)).thenReturn(summary(100L, UID, "PENDING_CONFLICT", List.of(301L), OffsetDateTime.now()));
        // 他人 summary 引用 301，13h 前总结 → 拒
        when(summaryMapper.findSummariesReferencingTurn(301L)).thenReturn(List.of(
                summary(500L, OTHER, "CLEAN", List.of(301L), OffsetDateTime.now().minusHours(13))));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.resolve(UID, 1L, "DISCARD"));
        assertTrue(ex.getMessage().contains("12h") || ex.getMessage().contains("KEEP"));
        verify(turnMapper, never()).softDeleteByIds(any());
        verify(summaryMapper, never()).softDeleteByIds(any());
    }

    // ---- 9. DISCARD 他人引用 <12h（recent）→ 通过 + STALE + 通知 + 清 coverage ----

    @Test
    void discardRecentOtherRefStaleAndNotify() {
        when(conflictMapper.selectById(1L)).thenReturn(pendingConflict(1L, 100L));
        when(summaryMapper.selectById(100L)).thenReturn(summary(100L, UID, "PENDING_CONFLICT", List.of(301L), OffsetDateTime.now()));
        when(summaryMapper.findSummariesReferencingTurn(301L)).thenReturn(List.of(
                summary(500L, OTHER, "CLEAN", List.of(301L), OffsetDateTime.now().minusHours(3))));

        service.resolve(UID, 1L, "DISCARD");

        verify(summaryMapper).markStatus(500L, "STALE");
        verify(coverageMapper).deleteBySummaryId(500L);
        verify(turnMapper).softDeleteByIds(eq(List.of(301L)));
        ArgumentCaptor<com.superprogrammer.chat.entity.MemoryNotification> nc =
                ArgumentCaptor.forClass(com.superprogrammer.chat.entity.MemoryNotification.class);
        verify(notificationMapper).insert(nc.capture());
        assertEquals(OTHER, nc.getValue().getUserId(), "通知发给他方作者");
        assertEquals("SUMMARY_AFFECTED_BY_RECALL", nc.getValue().getType());
    }

    // ---- 10. DISCARD 无 source turns（空 provenance）→ 不软删 turns 不抛 ----

    @Test
    void discardEmptySourceTurnsNoTurnDelete() {
        when(conflictMapper.selectById(1L)).thenReturn(pendingConflict(1L, 100L));
        when(summaryMapper.selectById(100L)).thenReturn(summary(100L, UID, "PENDING_CONFLICT", List.of(), OffsetDateTime.now()));

        service.resolve(UID, 1L, "DISCARD");

        verify(summaryMapper).softDeleteByIds(eq(List.of(100L)));
        verify(turnMapper, never()).softDeleteByIds(any());
    }
}
