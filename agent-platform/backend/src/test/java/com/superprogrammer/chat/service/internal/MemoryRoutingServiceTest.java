package com.superprogrammer.chat.service.internal;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.superprogrammer.chat.entity.MemoryProjectEntry;
import com.superprogrammer.chat.entity.MemoryProjectMember;
import com.superprogrammer.chat.entity.MemoryProjectRule;
import com.superprogrammer.chat.entity.MemoryTag;
import com.superprogrammer.chat.mapper.MemoryProjectEntryMapper;
import com.superprogrammer.chat.mapper.MemoryProjectMemberMapper;
import com.superprogrammer.chat.mapper.MemoryProjectRuleMapper;
import com.superprogrammer.chat.mapper.MemoryTagMapper;
import com.superprogrammer.system.service.SystemSettingService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskExecutor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 记忆二期 P1 · 路由器单测（FR-002/003/004/008）。
 * 纯 Mockito；核心断言：零 LLM 成本护栏、三档分流边界、双开关语义、黑名单降级。
 */
@ExtendWith(MockitoExtension.class)
class MemoryRoutingServiceTest {

    @Mock private MemoryProjectMemberMapper memberMapper;
    @Mock private MemoryProjectRuleMapper ruleMapper;
    @Mock private MemoryProjectEntryMapper entryMapper;
    @Mock private MemoryTagMapper tagMapper;
    @Mock private MemoryProjectRuleService ruleService;
    @Mock private MemoryGenToggleService toggleService;
    @Mock private MemoryTagAnchorService anchorService;
    @Mock private MemoryEntryDistiller distiller;
    @Mock private MemoryPrefilter prefilter;
    @Mock private SystemSettingService systemSettingService;
    @Mock private TaskExecutor memoryTaskExecutor;

    private MemoryRoutingService service;

    @BeforeAll
    static void initTableInfo() {
        org.apache.ibatis.session.Configuration cfg = new com.baomidou.mybatisplus.core.MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(cfg, "");
        TableInfoHelper.initTableInfo(assistant, MemoryProjectMember.class);
        TableInfoHelper.initTableInfo(assistant, MemoryProjectRule.class);
        TableInfoHelper.initTableInfo(assistant, MemoryProjectEntry.class);
    }

    @BeforeEach
    void setUp() {
        service = new MemoryRoutingService(memberMapper, ruleMapper, entryMapper, tagMapper,
                ruleService, toggleService, anchorService, distiller, prefilter,
                systemSettingService, memoryTaskExecutor);
        lenient().when(systemSettingService.getMemoryRoutingEnabled()).thenReturn(true);
        lenient().when(systemSettingService.getMemoryRoutingCoarseThreshold()).thenReturn(0.35);
        lenient().when(systemSettingService.getMemoryRoutingAutoApproveThreshold()).thenReturn(0.8);
        lenient().when(systemSettingService.getMemoryRoutingReviewThreshold()).thenReturn(0.5);
    }

    private MemoryProjectMember activeMember(long projectId) {
        MemoryProjectMember m = new MemoryProjectMember();
        m.setProjectId(projectId);
        m.setUserId(100L);
        m.setRole("MEMBER");
        m.setStatus("ACTIVE");
        return m;
    }

    private MemoryProjectRule rule(long id, long projectId) {
        MemoryProjectRule r = new MemoryProjectRule();
        r.setId(id);
        r.setProjectId(projectId);
        r.setRuleText("涉及 SeedDance 的讨论");
        r.setEnabled(true);
        return r;
    }

    private MemoryRoutingService.RoutingInput input() {
        return new MemoryRoutingService.RoutingInput(100L, 7L, 55L, "聊了 SeedDance cfg 参数", null, List.of());
    }

    /** 铺到「候选规则就绪」的公共 stub（粗筛前的链路）。 */
    private void stubToCandidates() {
        when(memberMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(activeMember(1L)));
        when(toggleService.resolveGenEnabled(100L, 1L)).thenReturn(true);
        when(ruleService.findRoutingCandidates(List.of(1L))).thenReturn(List.of(rule(9L, 1L)));
        when(anchorService.build(any(), any(), any(), anyString(), any()))
                .thenReturn(new MemoryTagAnchorService.AnchorPayload("[0.1]", "tok"));
    }

    // 总开关关 → 全停（不动任何 mapper）
    @Test
    void route_masterSwitchOff_skips() {
        when(systemSettingService.getMemoryRoutingEnabled()).thenReturn(false);
        service.route(input());
        verify(memberMapper, never()).selectList(any(LambdaQueryWrapper.class));
    }

    // 无 ACTIVE 项目 → 跳过零 LLM
    @Test
    void route_noActiveProjects_skips() {
        when(memberMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        service.route(input());
        verify(distiller, never()).judge(any(), any(), any(), any());
    }

    // AC-FR-008：会员关「允许被路由」覆写 → 该项目零条目
    @Test
    void route_memberOverrideOff_noEntries() {
        when(memberMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(activeMember(1L)));
        when(toggleService.resolveGenEnabled(100L, 1L)).thenReturn(false);
        service.route(input());
        verify(ruleService, never()).findRoutingCandidates(anyList());
        verify(entryMapper, never()).insert(any(MemoryProjectEntry.class));
    }

    // AC-FR-002：无候选规则 → 零 LLM
    @Test
    void route_noCandidateRules_zeroLlm() {
        when(memberMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(activeMember(1L)));
        when(toggleService.resolveGenEnabled(100L, 1L)).thenReturn(true);
        when(ruleService.findRoutingCandidates(anyList())).thenReturn(List.of());
        service.route(input());
        verify(distiller, never()).judge(any(), any(), any(), any());
    }

    // AC-FR-002：粗筛不过阈值 → 零 LLM 调用
    @Test
    void route_coarseMiss_zeroLlm() {
        stubToCandidates();
        when(ruleMapper.findWithinAnchorThreshold(anyList(), anyString(), anyDouble(), anyInt())).thenReturn(List.of());
        when(ruleMapper.rankByAnchorTsv(anyList(), anyString(), anyInt())).thenReturn(List.of());
        service.route(input());
        verify(distiller, never()).judge(any(), any(), any(), any());
        verify(entryMapper, never()).insert(any(MemoryProjectEntry.class));
    }

    // AC-FR-004：三档分流——0.9 ACTIVE / 0.6 PENDING_REVIEW / 0.3 丢弃
    @Test
    void route_confidenceSplit_threeBuckets() {
        stubToCandidates();
        when(ruleMapper.findWithinAnchorThreshold(anyList(), anyString(), anyDouble(), anyInt())).thenReturn(List.of(9L));
        when(ruleMapper.rankByAnchorTsv(anyList(), anyString(), anyInt())).thenReturn(List.of());
        when(distiller.judge(any(), anyList(), anyString(), any())).thenReturn(List.of(
                new MemoryEntryDistiller.Judgment(1L, true, 0.9, "直接收录", ""),
                new MemoryEntryDistiller.Judgment(1L, true, 0.6, "待审核", ""),
                new MemoryEntryDistiller.Judgment(1L, true, 0.3, "丢弃", "")));

        service.route(input());

        ArgumentCaptor<MemoryProjectEntry> captor = ArgumentCaptor.forClass(MemoryProjectEntry.class);
        verify(entryMapper, times(2)).insert(captor.capture());
        assertEquals(MemoryProjectEntry.STATUS_ACTIVE, captor.getAllValues().get(0).getStatus());
        assertEquals(MemoryProjectEntry.STATUS_PENDING_REVIEW, captor.getAllValues().get(1).getStatus());
    }

    // 边界值：恰 0.8 → ACTIVE；恰 0.5 → PENDING_REVIEW
    @Test
    void route_confidenceBoundary_exact() {
        stubToCandidates();
        when(ruleMapper.findWithinAnchorThreshold(anyList(), anyString(), anyDouble(), anyInt())).thenReturn(List.of(9L));
        when(ruleMapper.rankByAnchorTsv(anyList(), anyString(), anyInt())).thenReturn(List.of());
        when(distiller.judge(any(), anyList(), anyString(), any())).thenReturn(List.of(
                new MemoryEntryDistiller.Judgment(1L, true, 0.8, "边界上", ""),
                new MemoryEntryDistiller.Judgment(1L, true, 0.5, "边界下", "")));

        service.route(input());

        ArgumentCaptor<MemoryProjectEntry> captor = ArgumentCaptor.forClass(MemoryProjectEntry.class);
        verify(entryMapper, times(2)).insert(captor.capture());
        assertEquals(MemoryProjectEntry.STATUS_ACTIVE, captor.getAllValues().get(0).getStatus());
        assertEquals(MemoryProjectEntry.STATUS_PENDING_REVIEW, captor.getAllValues().get(1).getStatus());
    }

    // 设计 §9-16：蒸馏文本命中敏感黑名单 → ACTIVE 降 PENDING_REVIEW
    @Test
    void route_blacklistHit_downgradesToPending() {
        stubToCandidates();
        when(ruleMapper.findWithinAnchorThreshold(anyList(), anyString(), anyDouble(), anyInt())).thenReturn(List.of(9L));
        when(ruleMapper.rankByAnchorTsv(anyList(), anyString(), anyInt())).thenReturn(List.of());
        when(distiller.judge(any(), anyList(), anyString(), any())).thenReturn(List.of(
                new MemoryEntryDistiller.Judgment(1L, true, 0.95, "密码是 123456 的讨论", "")));
        when(prefilter.hitsBlacklist(anyString())).thenReturn(true);

        service.route(input());

        ArgumentCaptor<MemoryProjectEntry> captor = ArgumentCaptor.forClass(MemoryProjectEntry.class);
        verify(entryMapper).insert(captor.capture());
        assertEquals(MemoryProjectEntry.STATUS_PENDING_REVIEW, captor.getValue().getStatus());
    }

    // hit=false → 不落库
    @Test
    void route_notHit_noInsert() {
        stubToCandidates();
        when(ruleMapper.findWithinAnchorThreshold(anyList(), anyString(), anyDouble(), anyInt())).thenReturn(List.of(9L));
        when(ruleMapper.rankByAnchorTsv(anyList(), anyString(), anyInt())).thenReturn(List.of());
        when(distiller.judge(any(), anyList(), anyString(), any())).thenReturn(List.of(
                new MemoryEntryDistiller.Judgment(1L, false, 0.9, null, null)));
        service.route(input());
        verify(entryMapper, never()).insert(any(MemoryProjectEntry.class));
    }

    // 查询锚点构建失败（embed 故障）→ 降级不收录
    @Test
    void route_anchorBuildFails_degrades() {
        when(memberMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(activeMember(1L)));
        when(toggleService.resolveGenEnabled(100L, 1L)).thenReturn(true);
        when(ruleService.findRoutingCandidates(anyList())).thenReturn(List.of(rule(9L, 1L)));
        when(anchorService.build(any(), any(), any(), anyString(), any())).thenReturn(null);
        service.route(input());
        verify(ruleMapper, never()).findWithinAnchorThreshold(anyList(), anyString(), anyDouble(), anyInt());
    }

    // RRF 合并：向量优先 + BM25 补尾 + cap K
    @Test
    void mergeRrf_vectorFirstCapK() {
        List<Long> merged = MemoryRoutingService.mergeRrf(List.of(1L, 2L), List.of(2L, 3L, 4L), 3);
        assertEquals(List.of(1L, 2L, 3L), merged);
        assertTrue(MemoryRoutingService.mergeRrf(List.of(), List.of(), 3).isEmpty());
    }

    // ============================ P3 Step 4 · FR-204 文件记忆路由 ============================

    private MemoryRoutingService.RoutingInput fileInput() {
        return MemoryRoutingService.RoutingInput.ofFile(100L, "f-abc.pdf",
                "《课件.pdf》：讲 hooks 原理", "1. 原理", List.of(11L));
    }

    private void stubFileRouteHit() {
        stubToCandidates();
        when(ruleMapper.findWithinAnchorThreshold(anyList(), anyString(), anyDouble(), anyInt())).thenReturn(List.of(9L));
        when(ruleMapper.rankByAnchorTsv(anyList(), anyString(), anyInt())).thenReturn(List.of());
        when(distiller.judge(any(), anyList(), anyString(), any())).thenReturn(List.of(
                new MemoryEntryDistiller.Judgment(1L, true, 0.9, "文件：hooks 课件", "详述")));
    }

    // AC-FR-204：文件总结路由命中 → content_type=FILE + file_id 落库
    @Test
    void route_fileHit_insertsFileEntry() {
        stubFileRouteHit();
        when(entryMapper.countFileEntry(1L, "f-abc.pdf")).thenReturn(0L);

        service.route(fileInput());

        ArgumentCaptor<MemoryProjectEntry> captor = ArgumentCaptor.forClass(MemoryProjectEntry.class);
        verify(entryMapper).insert(captor.capture());
        MemoryProjectEntry e = captor.getValue();
        assertEquals(MemoryProjectEntry.CONTENT_TYPE_FILE, e.getContentType());
        assertEquals("f-abc.pdf", e.getFileId());
        assertEquals(MemoryProjectEntry.STATUS_ACTIVE, e.getStatus());
        assertEquals(null, e.getSourceTurnId(), "文件条目无 sourceTurn");
    }

    // AC-FR-204 幂等：同项目同文件已有条目 → 跳过不重复收录
    @Test
    void route_fileDuplicate_skips() {
        stubFileRouteHit();
        when(entryMapper.countFileEntry(1L, "f-abc.pdf")).thenReturn(1L);

        service.route(fileInput());

        verify(entryMapper, never()).insert(any(MemoryProjectEntry.class));
    }

    // 对话轮路由（fileId=null）→ 不查重、TEXT 条目（回归旧路径）
    @Test
    void route_turnInput_stillTextEntry() {
        stubFileRouteHit();
        service.route(input());
        ArgumentCaptor<MemoryProjectEntry> captor = ArgumentCaptor.forClass(MemoryProjectEntry.class);
        verify(entryMapper).insert(captor.capture());
        assertEquals(MemoryProjectEntry.CONTENT_TYPE_TEXT, captor.getValue().getContentType());
        assertEquals(null, captor.getValue().getFileId());
        verify(entryMapper, never()).countFileEntry(any(), any());
    }
}
