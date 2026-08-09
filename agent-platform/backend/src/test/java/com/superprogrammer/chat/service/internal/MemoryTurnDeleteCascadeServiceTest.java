package com.superprogrammer.chat.service.internal;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import com.superprogrammer.chat.entity.MemoryNotification;
import com.superprogrammer.chat.entity.MemoryProjectMember;
import com.superprogrammer.chat.entity.MemorySummary;
import com.superprogrammer.chat.mapper.MemoryEntryCoverageMapper;
import com.superprogrammer.chat.mapper.MemoryNotificationMapper;
import com.superprogrammer.chat.mapper.MemoryProjectEntryMapper;
import com.superprogrammer.chat.mapper.MemoryProjectMemberMapper;
import com.superprogrammer.chat.mapper.MemorySummaryCoverageMapper;
import com.superprogrammer.chat.mapper.MemorySummaryMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 记忆二期 P4 · MemoryTurnDeleteCascadeService 单测（FR-304）。
 * 验：条目级联软删 / 双路波及收集去重 / 已 STALE 幂等跳过 / 共享总结通知 owner/admin / 个人总结通知作者。
 */
@ExtendWith(MockitoExtension.class)
class MemoryTurnDeleteCascadeServiceTest {

    @BeforeAll
    static void initMpLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, MemoryProjectMember.class);
    }

    @Mock MemoryProjectEntryMapper entryMapper;
    @Mock MemorySummaryMapper summaryMapper;
    @Mock MemorySummaryCoverageMapper coverageMapper;
    @Mock MemoryEntryCoverageMapper entryCoverageMapper;
    @Mock MemoryNotificationMapper notificationMapper;
    @Mock MemoryProjectMemberMapper memberMapper;

    @InjectMocks MemoryTurnDeleteCascadeService service;

    private static MemorySummary personalSummary(Long id, Long userId) {
        MemorySummary s = new MemorySummary();
        s.setId(id);
        s.setUserId(userId);
        s.setScopeOwner("USER");
        s.setStatus("CLEAN");
        return s;
    }

    private static MemorySummary projectSharedSummary(Long id, Long projectId) {
        MemorySummary s = new MemorySummary();
        s.setId(id);
        s.setUserId(null);
        s.setProjectId(projectId);
        s.setScopeOwner("PROJECT");
        s.setStatus("CLEAN");
        return s;
    }

    // ---- 1. 空 turnIds → 全不动 ----

    @Test
    void emptyTurnIdsNoop() {
        service.cascadeAfterTurnsDeleted(1L, List.of());

        verify(entryMapper, never()).findActiveIdsBySourceTurnIds(any());
        verify(summaryMapper, never()).markStatus(anyLong(), any());
    }

    // ---- 2. turn 喂过项目条目 → 条目级联软删 + 条目路波及共享总结 STALE + 通知 owner/admin ----

    @Test
    void cascadesEntrySoftDeleteAndProjectSharedStale() {
        when(entryMapper.findActiveIdsBySourceTurnIds(List.of(301L))).thenReturn(List.of(88L));
        when(summaryMapper.findSummariesReferencingTurn(301L)).thenReturn(List.of());
        when(summaryMapper.findSummariesReferencingEntry(88L))
                .thenReturn(List.of(projectSharedSummary(600L, 99L)));
        MemoryProjectMember owner = new MemoryProjectMember();
        owner.setUserId(7L); owner.setRole("OWNER"); owner.setStatus("ACTIVE");
        when(memberMapper.selectList(any())).thenReturn(List.of(owner));

        service.cascadeAfterTurnsDeleted(1L, List.of(301L));

        verify(entryMapper).softDeleteBySourceTurnIds(List.of(301L));
        verify(summaryMapper).markStatus(600L, "STALE");
        verify(coverageMapper).deleteBySummaryId(600L);
        verify(entryCoverageMapper).deleteBySummaryId(600L);
        ArgumentCaptor<MemoryNotification> nc = ArgumentCaptor.forClass(MemoryNotification.class);
        verify(notificationMapper).insert(nc.capture());
        assertEquals(7L, nc.getValue().getUserId(), "共享总结波及通知发项目 owner/admin");
        assertEquals("SUMMARY_AFFECTED_BY_RECALL", nc.getValue().getType());
    }

    // ---- 3. 个人总结波及 → 通知作者本人；同 summary 双路命中只标一次（去重）----

    @Test
    void personalSummaryNotifiedOnceWhenHitByBothPaths() {
        when(entryMapper.findActiveIdsBySourceTurnIds(List.of(301L))).thenReturn(List.of(88L));
        MemorySummary mine = personalSummary(500L, 2L);
        when(summaryMapper.findSummariesReferencingTurn(301L)).thenReturn(List.of(mine));
        when(summaryMapper.findSummariesReferencingEntry(88L)).thenReturn(List.of(mine));  // 双路同一条

        service.cascadeAfterTurnsDeleted(1L, List.of(301L));

        verify(summaryMapper, times(1)).markStatus(500L, "STALE");
        ArgumentCaptor<MemoryNotification> nc = ArgumentCaptor.forClass(MemoryNotification.class);
        verify(notificationMapper, times(1)).insert(nc.capture());
        assertEquals(2L, nc.getValue().getUserId(), "个人总结波及通知发作者本人");
    }

    // ---- 4. 已 STALE 的总结幂等跳过（同版本只标一次）----

    @Test
    void alreadyStaleSummarySkipped() {
        when(entryMapper.findActiveIdsBySourceTurnIds(List.of(301L))).thenReturn(List.of());
        MemorySummary stale = personalSummary(500L, 2L);
        stale.setStatus("STALE");
        when(summaryMapper.findSummariesReferencingTurn(301L)).thenReturn(List.of(stale));

        service.cascadeAfterTurnsDeleted(1L, List.of(301L));

        verify(summaryMapper, never()).markStatus(anyLong(), any());
        verify(notificationMapper, never()).insert(any());
    }

    // ---- 5. 无条目无波及 → 只查不写 ----

    @Test
    void noEntriesNoRefsNoWrites() {
        when(entryMapper.findActiveIdsBySourceTurnIds(List.of(301L))).thenReturn(List.of());
        when(summaryMapper.findSummariesReferencingTurn(301L)).thenReturn(List.of());

        service.cascadeAfterTurnsDeleted(1L, List.of(301L));

        verify(entryMapper, never()).softDeleteBySourceTurnIds(anyList());
        verify(summaryMapper, never()).markStatus(anyLong(), any());
        verify(coverageMapper, never()).deleteBySummaryId(anyLong());
        verify(notificationMapper, never()).insert(any());
    }
}
