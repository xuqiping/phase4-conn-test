package com.superprogrammer.chat.service.internal;

import com.superprogrammer.chat.dto.MemoryConsolidationScopeRequest;
import com.superprogrammer.chat.dto.MemoryProjectEntryVO;
import com.superprogrammer.chat.dto.RecallTagMeta;
import com.superprogrammer.chat.entity.MemorySummaryCoverage;
import com.superprogrammer.chat.entity.MemoryTag;
import com.superprogrammer.chat.entity.MemoryTurn;
import com.superprogrammer.chat.mapper.MemoryEntryCoverageMapper;
import com.superprogrammer.chat.mapper.MemoryProjectEntryMapper;
import com.superprogrammer.chat.mapper.MemoryProjectMemberMapper;
import com.superprogrammer.chat.mapper.MemorySummaryCoverageMapper;
import com.superprogrammer.chat.mapper.MemorySummaryMapper;
import com.superprogrammer.chat.mapper.MemoryTagMapper;
import com.superprogrammer.chat.mapper.MemoryTurnMapper;
import com.superprogrammer.chat.service.internal.MemoryConsolidationCompressor.CompressedEntrySummary;
import com.superprogrammer.chat.service.internal.MemoryConsolidationCompressor.CompressedSummary;
import com.superprogrammer.chat.service.internal.MemoryConsolidationService.SummarizeResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 计划12 · E-3 · MemoryConsolidationService 编排单测（Mockito）。
 * 验：scope 解析 / manual-vs-auto backfill / 未覆盖过滤 / 压缩委派 / 空跳幂等 / 越权作者过滤。
 * 冲突落库 + 原子写在 {@link MemoryConsolidationTxService}（mock），此处仅验编排正确委派。
 */
@ExtendWith(MockitoExtension.class)
class MemoryConsolidationServiceTest {

    @Mock MemoryTurnMapper turnMapper;
    @Mock MemorySummaryMapper summaryMapper;
    @Mock MemorySummaryCoverageMapper coverageMapper;
    @Mock MemoryTagMapper tagMapper;
    @Mock MemoryBackfillService backfillService;
    @Mock MemoryConsolidationCompressor compressor;
    @Mock MemoryConsolidationTxService txService;
    @Mock MemoryQueryCache queryCache;
    @Mock MemoryProjectEntryMapper entryMapper;
    @Mock MemoryEntryCoverageMapper entryCoverageMapper;
    @Mock MemoryProjectLinkService linkService;
    @Mock MemoryProjectMemberMapper memberMapper;

    @InjectMocks MemoryConsolidationService service;

    @BeforeEach
    void setBloatThreshold() {
        // @Value 字段单测不注入 → 反射设默认，防 bloatThreshold=0 误判
        ReflectionTestUtils.setField(service, "bloatThreshold", 5);
    }

    private static RecallTagMeta tag(long id, String label) {
        RecallTagMeta t = new RecallTagMeta();
        t.setId(id);
        t.setLabel(label);
        return t;
    }

    private static MemoryTurn turn(long id) {
        MemoryTurn t = new MemoryTurn();
        t.setId(id);
        return t;
    }

    private static MemorySummaryCoverage cov(long turnId, long tagId) {
        MemorySummaryCoverage c = new MemorySummaryCoverage();
        c.setTurnId(turnId);
        c.setTagId(tagId);
        c.setProjectId(null);
        c.setUserId(1L);
        return c;
    }

    private MemoryConsolidationScopeRequest personalReq() {
        MemoryConsolidationScopeRequest r = new MemoryConsolidationScopeRequest();
        r.setScopeKind("PERSONAL");
        return r;
    }

    // ---- 1. personal happy path：未覆盖 → 压缩 → 委派 TxService ----

    @Test
    void personalHappyPathCompressAndDelegate() {
        when(tagMapper.findPersonalRecallTags(eq(1L), any(), any(), any(), any()))
                .thenReturn(List.of(tag(10L, "工作")));
        when(turnMapper.findPersonalTurnsForConsolidation(eq(1L), eq(List.of(10L)), any(), any(), any(), any()))
                .thenReturn(List.of(turn(101L), turn(102L)));
        when(coverageMapper.findByUserAndTurns(eq(1L), any())).thenReturn(List.of());  // 全未覆盖
        CompressedSummary cs = new CompressedSummary("L1", "L2", List.of(101L, 102L));
        when(compressor.compress(eq(1L), eq("工作"), any())).thenReturn(cs);

        SummarizeResult r = service.summarizeScope(1L, personalReq(), false);

        ArgumentCaptor<List<MemoryTurn>> captor = ArgumentCaptor.forClass(List.class);
        verify(txService).writeSummaryAndCoverage(eq(1L), eq(null), eq(10L), eq("工作"), eq("BOTH"), captor.capture(), eq(cs), any());
        assertEquals(2, captor.getValue().size(), "两条未覆盖 turn 委派写入");
        // evict 由真实 TxService 累加 summariesWritten 触发；txService mock 不累加，evict 在 IT 验
    }

    // ---- 2a. manual 触发 backfill ----

    @Test
    void manualTriggersBackfill() {
        when(tagMapper.findPersonalRecallTags(any(), any(), any(), any(), any())).thenReturn(List.of());

        service.summarizeScope(1L, personalReq(), true);

        verify(backfillService).backfillScope(eq(1L));
    }

    // ---- 2b. auto 不 backfill ----

    @Test
    void autoDoesNotBackfill() {
        when(tagMapper.findPersonalRecallTags(any(), any(), any(), any(), any())).thenReturn(List.of());

        service.summarizeScope(1L, personalReq(), false);

        verify(backfillService, never()).backfillScope(anyLong());
    }

    // ---- 3. 全已覆盖 → 空跳过，不调压缩 LLM（幂等）----

    @Test
    void allCoveredSkipsCompressAndWrite() {
        when(tagMapper.findPersonalRecallTags(any(), any(), any(), any(), any()))
                .thenReturn(List.of(tag(10L, "工作")));
        when(turnMapper.findPersonalTurnsForConsolidation(eq(1L), eq(List.of(10L)), any(), any(), any(), any()))
                .thenReturn(List.of(turn(101L)));
        // turn 101 已被 tag 10 覆盖（个人 scope project_id=null）
        when(coverageMapper.findByUserAndTurns(eq(1L), any())).thenReturn(List.of(cov(101L, 10L)));

        SummarizeResult r = service.summarizeScope(1L, personalReq(), false);

        verify(compressor, never()).compress(anyLong(), any(), any());
        verify(txService, never()).writeSummaryAndCoverage(anyLong(), any(), anyLong(), any(), any(), any(), any(), any());
        assertEquals(0, r.getSummariesWritten());
    }

    // ---- 4. 压缩 null（LLM 失败/日期铁律违则）→ skip + note ----

    @Test
    void compressNullSkipsWithNote() {
        when(tagMapper.findPersonalRecallTags(any(), any(), any(), any(), any()))
                .thenReturn(List.of(tag(10L, "工作")));
        when(turnMapper.findPersonalTurnsForConsolidation(eq(1L), eq(List.of(10L)), any(), any(), any(), any()))
                .thenReturn(List.of(turn(101L)));
        when(coverageMapper.findByUserAndTurns(eq(1L), any())).thenReturn(List.of());
        when(compressor.compress(anyLong(), any(), any())).thenReturn(null);

        SummarizeResult r = service.summarizeScope(1L, personalReq(), false);

        verify(txService, never()).writeSummaryAndCoverage(anyLong(), any(), anyLong(), any(), any(), any(), any(), any());
        assertTrue(r.getNotes().stream().anyMatch(n -> n.contains("压缩失败")));
    }

    // ---- 5. 二期人工测试 Req1：非创始人(OWNER) 触发项目共享总结 → 权限咽喉拦截 skip ----

    @Test
    void projectSharedNonOwnerSkipped() {
        MemoryConsolidationScopeRequest req = new MemoryConsolidationScopeRequest();
        req.setScopeKind("PROJECT");
        req.setProjectId(99L);
        when(linkService.isOwner(99L, 1L)).thenReturn(false);

        SummarizeResult r = service.summarizeScope(1L, req, false);

        assertTrue(r.getNotes().stream().anyMatch(n -> n.contains("仅创始人")), "非创始人总结越权 note");
        assertEquals(0, r.getSummariesWritten());
        verifyNoInteractions(entryMapper, entryCoverageMapper, compressor, txService);
    }

    // ---- 6. 二期人工测试 Req1：非创始人(OWNER) 个人压缩通道 → 同样拦截（项目总结仅创始人）----

    @Test
    void projectPersonalNonOwnerSkipped() {
        MemoryConsolidationScopeRequest req = new MemoryConsolidationScopeRequest();
        req.setScopeKind("PROJECT");
        req.setProjectId(99L);
        req.setToPersonal(true);
        when(linkService.isOwner(99L, 1L)).thenReturn(false);

        SummarizeResult r = service.summarizeScope(1L, req, false);

        assertTrue(r.getNotes().stream().anyMatch(n -> n.contains("仅创始人")), "非创始人个人压缩越权 note");
        verifyNoInteractions(entryMapper, entryCoverageMapper, compressor, txService);
    }

    // ---- 7. 二期 P4：共享总结 happy path——嵌套取数含 child + 未覆盖压缩 + 委派写（FR-301/303/305）----

    @Test
    void projectSharedHappyPath() {
        MemoryConsolidationScopeRequest req = new MemoryConsolidationScopeRequest();
        req.setScopeKind("PROJECT");
        req.setProjectId(99L);
        when(linkService.isOwner(99L, 1L)).thenReturn(true);
        when(linkService.findActiveChildIds(List.of(99L))).thenReturn(List.of(77L));  // ACTIVE child
        MemoryProjectEntryVO e1 = MemoryProjectEntryVO.builder()
                .id(201L).projectId(99L).tagIds(List.of(10L)).l1Summary("条目一").build();
        MemoryProjectEntryVO e2 = MemoryProjectEntryVO.builder()
                .id(202L).projectId(77L).tagIds(List.of(10L)).l1Summary("child 条目").build();
        when(entryMapper.listActiveForRecall(List.of(99L, 77L))).thenReturn(List.of(e1, e2));
        MemoryTag tag = new MemoryTag();
        tag.setId(10L);
        tag.setLabel("工作");
        when(tagMapper.selectBatchIds(any())).thenReturn(List.of(tag));
        when(entryCoverageMapper.findCoveredEntryIds(any(), eq(99L), eq(10L), eq(null)))
                .thenReturn(List.of(201L));  // e1 已覆盖，e2 未覆盖
        CompressedEntrySummary cs = new CompressedEntrySummary("L1", "L2", List.of(202L));
        when(compressor.compressEntries(eq(1L), eq("工作"), any())).thenReturn(cs);

        SummarizeResult r = service.summarizeScope(1L, req, true);

        // 嵌套取数验证：查的是 [本项目, child]
        verify(entryMapper).listActiveForRecall(List.of(99L, 77L));
        // 覆盖行查共享通道（user_id=null）
        verify(entryCoverageMapper).findCoveredEntryIds(any(), eq(99L), eq(10L), eq(null));
        // 仅未覆盖的 e2 委派写（shared=true）
        ArgumentCaptor<List<MemoryProjectEntryVO>> captor = ArgumentCaptor.forClass(List.class);
        verify(txService).writeProjectSummaryAndCoverage(eq(1L), eq(99L), eq(true), eq(10L), eq("BOTH"), captor.capture(), eq(cs), any());
        assertEquals(1, captor.getValue().size(), "已覆盖条目不重压（幂等）");
        assertEquals(202L, captor.getValue().get(0).getId());
    }

    // ---- 8. 二期 P4：创始人个人压缩 happy path——user_id=self 通道 + shared=false（FR-302）----

    @Test
    void projectPersonalHappyPath() {
        MemoryConsolidationScopeRequest req = new MemoryConsolidationScopeRequest();
        req.setScopeKind("PROJECT");
        req.setProjectId(99L);
        req.setToPersonal(true);
        when(linkService.isOwner(99L, 1L)).thenReturn(true);
        when(linkService.findActiveChildIds(List.of(99L))).thenReturn(List.of());
        MemoryProjectEntryVO e1 = MemoryProjectEntryVO.builder()
                .id(201L).projectId(99L).tagIds(List.of(10L)).l1Summary("条目一").build();
        when(entryMapper.listActiveForRecall(List.of(99L))).thenReturn(List.of(e1));
        MemoryTag tag = new MemoryTag();
        tag.setId(10L);
        tag.setLabel("工作");
        when(tagMapper.selectBatchIds(any())).thenReturn(List.of(tag));
        when(entryCoverageMapper.findCoveredEntryIds(any(), eq(99L), eq(10L), eq(1L)))
                .thenReturn(List.of());  // 个人通道未覆盖
        CompressedEntrySummary cs = new CompressedEntrySummary("L1", "L2", List.of(201L));
        when(compressor.compressEntries(eq(1L), eq("工作"), any())).thenReturn(cs);

        service.summarizeScope(1L, req, true);

        // 个人通道覆盖行 user_id=self（与共享通道互不影响）
        verify(entryCoverageMapper).findCoveredEntryIds(any(), eq(99L), eq(10L), eq(1L));
        verify(txService).writeProjectSummaryAndCoverage(eq(1L), eq(99L), eq(false), eq(10L), eq("BOTH"), any(), eq(cs), any());
    }

    // ---- 9. 二期 P4：全条目已覆盖 → 空跳过不调 LLM（幂等）----

    @Test
    void projectAllCoveredSkipsCompress() {
        MemoryConsolidationScopeRequest req = new MemoryConsolidationScopeRequest();
        req.setScopeKind("PROJECT");
        req.setProjectId(99L);
        when(linkService.isOwner(99L, 1L)).thenReturn(true);
        when(linkService.findActiveChildIds(List.of(99L))).thenReturn(List.of());
        MemoryProjectEntryVO e1 = MemoryProjectEntryVO.builder()
                .id(201L).projectId(99L).tagIds(List.of(10L)).l1Summary("条目一").build();
        when(entryMapper.listActiveForRecall(List.of(99L))).thenReturn(List.of(e1));
        MemoryTag tag = new MemoryTag();
        tag.setId(10L);
        tag.setLabel("工作");
        when(tagMapper.selectBatchIds(any())).thenReturn(List.of(tag));
        when(entryCoverageMapper.findCoveredEntryIds(any(), eq(99L), eq(10L), eq(null)))
                .thenReturn(List.of(201L));  // 全已覆盖

        SummarizeResult r = service.summarizeScope(1L, req, false);

        verify(compressor, never()).compressEntries(anyLong(), any(), any());
        verify(txService, never()).writeProjectSummaryAndCoverage(anyLong(), anyLong(), eq(true), anyLong(), any(), any(), any(), any());
        assertEquals(0, r.getSummariesWritten());
    }

    // ---- 9b. 二期人工测试 Req2：force=true（重新总结）→ 跳过未覆盖闸，全已覆盖也强制重压 ----

    @Test
    void projectForceResummarizeIgnoresCoverage() {
        MemoryConsolidationScopeRequest req = new MemoryConsolidationScopeRequest();
        req.setScopeKind("PROJECT");
        req.setProjectId(99L);
        req.setForce(true);
        when(linkService.isOwner(99L, 1L)).thenReturn(true);
        when(linkService.findActiveChildIds(List.of(99L))).thenReturn(List.of());
        MemoryProjectEntryVO e1 = MemoryProjectEntryVO.builder()
                .id(201L).projectId(99L).tagIds(List.of(10L)).l1Summary("条目一").build();
        when(entryMapper.listActiveForRecall(List.of(99L))).thenReturn(List.of(e1));
        MemoryTag tag = new MemoryTag();
        tag.setId(10L);
        tag.setLabel("工作");
        when(tagMapper.selectBatchIds(any())).thenReturn(List.of(tag));
        // force 下不查覆盖行（直接当全未覆盖重压）→ 无需 stub findCoveredEntryIds
        CompressedEntrySummary cs = new CompressedEntrySummary("L1", "L2", List.of(201L));
        when(compressor.compressEntries(eq(1L), eq("工作"), any())).thenReturn(cs);

        SummarizeResult r = service.summarizeScope(1L, req, false);

        // force 跳过覆盖闸：不查 findCoveredEntryIds，直接压缩已覆盖的 e1
        verify(entryCoverageMapper, never()).findCoveredEntryIds(any(), any(), any(), any());
        verify(compressor).compressEntries(eq(1L), eq("工作"), any());
        verify(txService).writeProjectSummaryAndCoverage(eq(1L), eq(99L), eq(true), eq(10L), eq("BOTH"), any(), eq(cs), any());
    }

    // ---- 10. scope 无标签 → 空结果 ----

    @Test
    void emptyTagsEmptyResult() {
        when(tagMapper.findPersonalRecallTags(any(), any(), any(), any(), any())).thenReturn(List.of());

        SummarizeResult r = service.summarizeScope(1L, personalReq(), false);

        assertEquals(0, r.getSummariesWritten());
        verify(compressor, never()).compress(anyLong(), any(), any());
    }

    // ---- 8. 防膨胀触发：count > 阈值 → note ----

    @Test
    void bloatThresholdAddsNote() {
        ReflectionTestUtils.setField(service, "bloatThreshold", 2);
        when(tagMapper.findPersonalRecallTags(any(), any(), any(), any(), any()))
                .thenReturn(List.of(tag(10L, "工作")));
        when(turnMapper.findPersonalTurnsForConsolidation(eq(1L), eq(List.of(10L)), any(), any(), any(), any()))
                .thenReturn(List.of(turn(101L)));
        when(coverageMapper.findByUserAndTurns(eq(1L), any())).thenReturn(List.of());
        when(compressor.compress(anyLong(), any(), any())).thenReturn(new CompressedSummary("L1", "L2", List.of(101L)));
        when(summaryMapper.countByUserTagScope(eq(1L), eq(10L), any())).thenReturn(3);  // > 2 阈值

        SummarizeResult r = service.summarizeScope(1L, personalReq(), false);

        assertTrue(r.getNotes().stream().anyMatch(n -> n.contains("防膨胀")), "count>阈值 → 防膨胀 note");
    }
}
