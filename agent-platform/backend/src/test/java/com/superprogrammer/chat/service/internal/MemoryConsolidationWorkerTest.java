package com.superprogrammer.chat.service.internal;

import com.superprogrammer.chat.dto.MemoryProjectEntryVO;
import com.superprogrammer.chat.entity.MemoryConsolidationScope;
import com.superprogrammer.chat.entity.MemorySummary;
import com.superprogrammer.chat.entity.MemoryTag;
import com.superprogrammer.chat.entity.MemoryTurn;
import com.superprogrammer.chat.mapper.MemoryConsolidationScopeMapper;
import com.superprogrammer.chat.mapper.MemoryEntryCoverageMapper;
import com.superprogrammer.chat.mapper.MemoryProjectEntryMapper;
import com.superprogrammer.chat.mapper.MemorySummaryCoverageMapper;
import com.superprogrammer.chat.mapper.MemorySummaryMapper;
import com.superprogrammer.chat.mapper.MemoryTagMapper;
import com.superprogrammer.chat.mapper.MemoryTurnMapper;
import com.superprogrammer.chat.service.internal.MemoryConsolidationCompressor.CompressedEntrySummary;
import com.superprogrammer.chat.service.internal.MemoryConsolidationCompressor.CompressedSummary;
import com.superprogrammer.chat.service.internal.MemoryConsolidationService.SummarizeResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 计划12 · E-6 · MemoryConsolidationWorker 单测。
 * 验：认领空跳 / 多 scope 处理 + 释放锁 / 失败释放失败锁 / STALE 重生路径。
 */
@ExtendWith(MockitoExtension.class)
class MemoryConsolidationWorkerTest {

    @Mock MemoryConsolidationTxService txService;
    @Mock MemoryConsolidationService consolidationService;
    @Mock MemoryConsolidationCompressor compressor;
    @Mock MemoryConsolidationScopeMapper scopeMapper;
    @Mock MemorySummaryMapper summaryMapper;
    @Mock MemoryTurnMapper turnMapper;
    @Mock MemoryTagMapper tagMapper;
    @Mock MemorySummaryCoverageMapper coverageMapper;
    @Mock MemoryProjectEntryMapper entryMapper;
    @Mock MemoryEntryCoverageMapper entryCoverageMapper;
    @Mock MemoryProjectLinkService linkService;

    @InjectMocks MemoryConsolidationWorker worker;

    private static MemoryConsolidationScope personalScope(Long id, Long userId) {
        MemoryConsolidationScope s = new MemoryConsolidationScope();
        s.setId(id);
        s.setUserId(userId);
        s.setScopeKind("PERSONAL");
        s.setProjectId(null);
        s.setAutoEnabled(true);
        return s;
    }

    // ---- 1. 认领空 → 不处理 ----

    @Test
    void pollAutoEmptyClaimNoop() {
        when(txService.claimAutoScopes(anyInt(), any(), any(), anyInt())).thenReturn(List.of());

        worker.pollAuto();

        verify(consolidationService, never()).summarizeScope(anyLong(), any(), org.mockito.ArgumentMatchers.anyBoolean());
        verify(scopeMapper, never()).releaseLockSuccess(anyLong(), any());
    }

    // ---- 2. 认领多 scope → 各 summarizeScope + 释放锁成功 + STALE 重生（无 STALE）----

    @Test
    void pollAutoProcessesScopesAndReleasesLock() {
        MemoryConsolidationScope s1 = personalScope(1L, 10L);
        MemoryConsolidationScope s2 = personalScope(2L, 20L);
        when(txService.claimAutoScopes(anyInt(), any(), any(), anyInt()))
                .thenReturn(List.of(s1, s2));
        when(consolidationService.summarizeScope(anyLong(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(new SummarizeResult());
        when(summaryMapper.findStaleByUser(anyLong())).thenReturn(List.of());  // 无 STALE

        worker.pollAuto();

        verify(consolidationService).summarizeScope(eq(10L), any(), eq(false));
        verify(consolidationService).summarizeScope(eq(20L), any(), eq(false));
        verify(scopeMapper).releaseLockSuccess(eq(1L), any());
        verify(scopeMapper).releaseLockSuccess(eq(2L), any());
        verify(scopeMapper, never()).releaseLockFailure(anyLong());
    }

    // ---- 3. summarizeScope 抛 → 释放失败锁（保留 last_run_at 允许重试）----

    @Test
    void pollAutoFailureReleasesFailureLock() {
        when(txService.claimAutoScopes(anyInt(), any(), any(), anyInt()))
                .thenReturn(List.of(personalScope(1L, 10L)));
        when(consolidationService.summarizeScope(anyLong(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenThrow(new RuntimeException("LLM 宕机"));
        when(summaryMapper.findStaleByUser(anyLong())).thenReturn(List.of());

        worker.pollAuto();

        verify(scopeMapper).releaseLockFailure(1L);
        verify(scopeMapper, never()).releaseLockSuccess(anyLong(), any());
    }

    // ---- 4. STALE 重生：剩余 turn → 压缩 → updateTextAndStatus CLEAN + coverage 重建 ----

    @Test
    void regenStaleCompressesRemainingTurnsToClean() {
        MemorySummary stale = new MemorySummary();
        stale.setId(500L);
        stale.setUserId(10L);
        stale.setTagId(7L);
        stale.setProjectId(null);
        stale.setSourceTurnIds(List.of(301L, 302L));
        when(summaryMapper.findStaleByUser(10L)).thenReturn(List.of(stale));
        MemoryTurn t1 = new MemoryTurn(); t1.setId(301L);
        MemoryTurn t2 = new MemoryTurn(); t2.setId(302L);
        when(turnMapper.findTurnsByIds(List.of(301L, 302L))).thenReturn(List.of(t1, t2));
        MemoryTag tag = new MemoryTag(); tag.setLabel("工作");
        when(tagMapper.selectById(7L)).thenReturn(tag);
        when(compressor.compress(eq(10L), eq("工作"), any()))
                .thenReturn(new CompressedSummary("重生L1", "重生L2", List.of(301L, 302L)));

        worker.regenStaleSummaries(10L);

        verify(summaryMapper).updateTextAndStatus(500L, "重生L1", "重生L2", "CLEAN");
        verify(coverageMapper).deleteBySummaryId(500L);
        verify(coverageMapper).batchInsert(any());
    }

    // ---- 5. STALE 重生：剩余 turn 空（全删）→ 软删 summary + 清 coverage ----

    @Test
    void regenStaleEmptyRemainingSoftDeletesSummary() {
        MemorySummary stale = new MemorySummary();
        stale.setId(500L);
        stale.setUserId(10L);
        stale.setTagId(7L);
        stale.setSourceTurnIds(List.of(301L));
        when(summaryMapper.findStaleByUser(10L)).thenReturn(List.of(stale));
        when(turnMapper.findTurnsByIds(List.of(301L))).thenReturn(List.of());  // 全软删

        worker.regenStaleSummaries(10L);

        verify(summaryMapper).softDeleteByIds(eq(List.of(500L)));
        verify(coverageMapper).deleteBySummaryId(500L);
        verify(compressor, never()).compress(anyLong(), any(), any());
    }

    // ---- 6. STALE 重生：压缩失败 → 保留 STALE（不改 status）下轮再试 ----

    @Test
    void regenStaleCompressFailKeepsStale() {
        MemorySummary stale = new MemorySummary();
        stale.setId(500L);
        stale.setUserId(10L);
        stale.setTagId(7L);
        stale.setSourceTurnIds(List.of(301L));
        when(summaryMapper.findStaleByUser(10L)).thenReturn(List.of(stale));
        MemoryTurn t = new MemoryTurn(); t.setId(301L);
        when(turnMapper.findTurnsByIds(List.of(301L))).thenReturn(List.of(t));
        when(tagMapper.selectById(7L)).thenReturn(new MemoryTag());
        when(compressor.compress(anyLong(), any(), any())).thenReturn(null);

        worker.regenStaleSummaries(10L);

        verify(summaryMapper, never()).updateTextAndStatus(anyLong(), any(), any(), any());
        verify(summaryMapper, never()).softDeleteByIds(any());
    }

    // ---- 7. 无 STALE → 不调压缩 ----

    @Test
    void regenStaleNoopWhenNone() {
        when(summaryMapper.findStaleByUser(10L)).thenReturn(List.of());

        worker.regenStaleSummaries(10L);

        verify(compressor, never()).compress(anyLong(), any(), any());
        verify(turnMapper, never()).findTurnsByIds(any());
    }

    // ============================ 二期 P4 · 条目级 STALE 重生（FR-303/304/305）============================

    private static MemorySummary staleProjectShared() {
        MemorySummary s = new MemorySummary();
        s.setId(600L);
        s.setUserId(null);                 // 项目资产
        s.setCreatedBy(10L);               // 触发者留痕 → LLM 计费
        s.setProjectId(99L);
        s.setTagId(7L);
        s.setScopeOwner("PROJECT");
        s.setStatus("STALE");
        s.setSourceTurnIds(List.of());
        s.setSourceEntryIds(List.of(201L));
        return s;
    }

    // ---- 8. P4 项目共享 STALE 重生：取数=当前 ACTIVE 链（child 77 合流）→ 重压 → CLEAN + 条目 coverage 重建（user_id NULL）----

    @Test
    void regenStaleProjectSharedHappyPath() {
        MemorySummary stale = staleProjectShared();
        when(summaryMapper.findStaleProjectShared()).thenReturn(List.of(stale));
        when(linkService.findActiveChildIds(List.of(99L))).thenReturn(List.of(77L));
        MemoryProjectEntryVO e1 = MemoryProjectEntryVO.builder()
                .id(201L).projectId(99L).tagIds(List.of(7L))
                .createdAt(OffsetDateTime.now().minusDays(2)).build();
        MemoryProjectEntryVO e2 = MemoryProjectEntryVO.builder()
                .id(202L).projectId(77L).tagIds(List.of(8L))   // 别的 tag → 过滤掉
                .createdAt(OffsetDateTime.now().minusDays(1)).build();
        when(entryMapper.listActiveForRecall(List.of(99L, 77L))).thenReturn(List.of(e1, e2));
        MemoryTag tag = new MemoryTag(); tag.setLabel("工作");
        when(tagMapper.selectById(7L)).thenReturn(tag);
        when(compressor.compressEntries(eq(10L), eq("工作"), any()))
                .thenReturn(new CompressedEntrySummary("新L1", "新L2", List.of(201L)));

        worker.regenStaleProjectShared();

        verify(summaryMapper).updateTextStatusAndEntries(eq(600L), eq("新L1"), eq("新L2"), eq("CLEAN"), eq(List.of(201L)));
        verify(entryCoverageMapper).deleteBySummaryId(600L);
        org.mockito.ArgumentCaptor<List<com.superprogrammer.chat.entity.MemoryEntryCoverage>> cap =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(entryCoverageMapper).batchInsert(cap.capture());
        org.junit.jupiter.api.Assertions.assertNull(cap.getValue().get(0).getUserId(),
                "共享总结 coverage user_id 须 NULL");
    }

    // ---- 9. P4 项目共享 STALE 重生：当前链条目空（授权全撤/条目全删）→ 软删 + 清条目 coverage ----

    @Test
    void regenStaleProjectSharedEmptyChainSoftDeletes() {
        MemorySummary stale = staleProjectShared();
        when(summaryMapper.findStaleProjectShared()).thenReturn(List.of(stale));
        when(linkService.findActiveChildIds(List.of(99L))).thenReturn(List.of());
        when(entryMapper.listActiveForRecall(List.of(99L))).thenReturn(List.of());

        worker.regenStaleProjectShared();

        verify(summaryMapper).softDeleteByIds(eq(List.of(600L)));
        verify(entryCoverageMapper).deleteBySummaryId(600L);
        verify(compressor, never()).compressEntries(anyLong(), any(), any());
    }

    // ---- 10. P4 成员个人压缩 STALE 重生：作者已非 ACTIVE 成员 → 软删（离职失读权），不重压 ----

    @Test
    void regenStaleMemberPersonalNonMemberSoftDeletes() {
        MemorySummary stale = new MemorySummary();
        stale.setId(601L);
        stale.setUserId(10L);
        stale.setProjectId(99L);
        stale.setTagId(7L);
        stale.setScopeOwner("USER");
        stale.setStatus("STALE");
        stale.setSourceTurnIds(List.of());
        stale.setSourceEntryIds(List.of(201L));
        when(summaryMapper.findStaleByUser(10L)).thenReturn(List.of(stale));
        when(linkService.isActiveMember(99L, 10L)).thenReturn(false);

        worker.regenStaleSummaries(10L);

        verify(summaryMapper).softDeleteByIds(eq(List.of(601L)));
        verify(entryCoverageMapper).deleteBySummaryId(601L);
        verify(compressor, never()).compressEntries(anyLong(), any(), any());
        verify(entryMapper, never()).listActiveForRecall(any());
    }
}
