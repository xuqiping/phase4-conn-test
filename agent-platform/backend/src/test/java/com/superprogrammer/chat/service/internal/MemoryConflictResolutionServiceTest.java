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
    @Mock MemoryProjectLinkService linkService;

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

    // ---- 8. 二期 P4（FR-304）：废 12h 拒删——他人引用 >12h 也不再 FORBIDDEN，统一 STALE + 通知 ----

    @Test
    void discardOldOtherRefNoLongerRejected_p4() {
        when(conflictMapper.selectById(1L)).thenReturn(pendingConflict(1L, 100L));
        when(summaryMapper.selectById(100L)).thenReturn(summary(100L, UID, "PENDING_CONFLICT", List.of(301L), OffsetDateTime.now()));
        // 他人 summary 引用 301，13h 前总结——P4 前会 12h 拒；废 12h 后照样 STALE
        when(summaryMapper.findSummariesReferencingTurn(301L)).thenReturn(List.of(
                summary(500L, OTHER, "CLEAN", List.of(301L), OffsetDateTime.now().minusHours(13))));

        service.resolve(UID, 1L, "DISCARD");

        verify(summaryMapper).softDeleteByIds(eq(List.of(100L)));   // 不再拒
        verify(turnMapper).softDeleteByIds(eq(List.of(301L)));
        verify(summaryMapper).markStatus(500L, "STALE");
        verify(notificationMapper).insert(any());
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

    // ============================ 二期 P4 · 项目共享总结冲突裁决（FR-303 裁决权随总结所有权）============================

    private static MemorySummary projectSharedSummary(Long id, String status) {
        MemorySummary s = new MemorySummary();
        s.setId(id);
        s.setUserId(null);                 // 项目资产
        s.setProjectId(99L);
        s.setTagId(10L);
        s.setScopeOwner("PROJECT");
        s.setStatus(status);
        s.setSourceTurnIds(List.of());
        s.setSourceEntryIds(List.of(201L));
        s.setSummarizedAt(OffsetDateTime.now());
        return s;
    }

    // ---- 11. P4 项目共享总结冲突：owner/admin 可裁决（conflict.user_id≠裁决者也行）----

    @Test
    void projectSharedConflictOwnerCanResolve() {
        when(conflictMapper.selectById(1L)).thenReturn(pendingConflict(1L, 100L));  // user_id=UID=触发者留痕
        when(summaryMapper.selectById(100L)).thenReturn(projectSharedSummary(100L, "PENDING_CONFLICT"));
        when(linkService.isOwnerOrAdmin(99L, OTHER)).thenReturn(true);  // 裁决者是另一 admin
        when(summaryMapper.findByProjectTagScopeStatus(99L, 10L, "PENDING_CONFLICT"))
                .thenReturn(List.of(projectSharedSummary(100L, "PENDING_CONFLICT")));

        boolean ok = service.resolve(OTHER, 1L, "KEEP_BOTH");

        assertTrue(ok);
        verify(summaryMapper).markStatus(100L, "CLEAN");
        verify(conflictMapper).markV47Resolved(1L, "KEEP_BOTH");
    }

    // ---- 12. P4 项目共享总结冲突：普通成员裁决 → NOT_FOUND（越权防探测）----

    @Test
    void projectSharedConflictMemberForbidden() {
        when(conflictMapper.selectById(1L)).thenReturn(pendingConflict(1L, 100L));
        when(summaryMapper.selectById(100L)).thenReturn(projectSharedSummary(100L, "PENDING_CONFLICT"));
        when(linkService.isOwnerOrAdmin(99L, OTHER)).thenReturn(false);

        assertThrows(BusinessException.class, () -> service.resolve(OTHER, 1L, "KEEP_BOTH"));
        verify(conflictMapper, never()).markV47Resolved(anyLong(), any());
    }

    // ---- 13. P4 项目共享总结 DISCARD：仅软删 summary，条目/turns 不动，覆盖留档防重压循环 ----

    @Test
    void projectSharedDiscardKeepsEntriesAndCoverage() {
        when(conflictMapper.selectById(1L)).thenReturn(pendingConflict(1L, 100L));
        when(summaryMapper.selectById(100L)).thenReturn(projectSharedSummary(100L, "PENDING_CONFLICT"));
        when(linkService.isOwnerOrAdmin(99L, UID)).thenReturn(true);

        service.resolve(UID, 1L, "DISCARD");

        verify(summaryMapper).softDeleteByIds(eq(List.of(100L)));
        verify(turnMapper, never()).softDeleteByIds(any());
        verify(coverageMapper, never()).deleteBySummaryId(anyLong());  // turn 级 coverage 不动
        verify(conflictMapper).markV47Resolved(1L, "DISCARD");
    }

    // ---- 14. P4 待裁决列表：本人冲突 ∪ 我 owner/admin 项目的共享冲突（去重 + projectShared 打标）----

    @Test
    void listPendingMergesProjectSharedForManagers() {
        MemoryConflict mine = pendingConflict(1L, 100L);
        MemoryConflict sharedDup = pendingConflict(1L, 100L);   // 同一条（触发者本人也是 admin）→ 去重
        MemoryConflict sharedNew = pendingConflict(2L, 200L);
        when(conflictMapper.findV47PendingByUser(UID)).thenReturn(List.of(mine));
        when(conflictMapper.findV47PendingProjectSharedByManager(UID)).thenReturn(List.of(sharedDup, sharedNew));

        List<MemoryConflict> out = service.listPending(UID);

        assertEquals(2, out.size(), "并集去重");
        assertNull(out.get(0).getProjectShared(), "本人冲突不打共享标");
        assertEquals(Boolean.TRUE, out.get(1).getProjectShared(), "共享冲突打标供前端 badge");
        assertEquals(2, service.countPending(UID));
    }
}
