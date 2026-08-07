package com.superprogrammer.chat.service.internal;

import com.superprogrammer.chat.dto.MemoryProjectEntryVO;
import com.superprogrammer.chat.dto.MemoryRecallResult;
import com.superprogrammer.chat.dto.MemoryRecallScopeRequest;
import com.superprogrammer.chat.dto.RecallTagMeta;
import com.superprogrammer.chat.dto.RecalledSummary;
import com.superprogrammer.chat.dto.RecallTraceStep;
import com.superprogrammer.chat.entity.MemorySummary;
import com.superprogrammer.chat.entity.MemoryTurn;
import com.superprogrammer.chat.mapper.MemoryTagMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 计划12 · D-6 · MemoryRecallPipeline 单测（Mockito，mock 5 子组件）。
 * <p>
 * 覆盖（对齐 §3.3 七步编排 + ⑦装配 + 降级链 + 运维打点）：
 * <ol>
 *   <li>空 scope → 返空装配、counts 0。</li>
 *   <li>happy path → 全步骤串行 + assembledText 含 summary 块与 turn 块。</li>
 *   <li>owner=self subject='我' → 省主体（无「我·」前缀）。</li>
 *   <li>owner=self subject='表哥' → 保留主体。</li>
 *   <li>owner≠self subject='我' → owner 前缀替代「我」。</li>
 *   <li>owner≠self subject='本人' → owner + 主体双前缀。</li>
 *   <li>includeL2=false → 只 L1；true → L1+L2。</li>
 *   <li>turn 装配 owner 前缀 + direction + rawContent。</li>
 *   <li>aggregate 抛 → degraded + notes 非空，turns 仍兜底。</li>
 *   <li>read 抛 → degraded，summaries=0，turns 仍拼。</li>
 *   <li>select 返空 → read 返空，patch 仍兜底 turns。</li>
 *   <li>selector 降级启发式（selected==tags 且 size&gt;30）→ degraded。</li>
 *   <li>steps 打点 6 步名齐全。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class MemoryRecallPipelineTest {

    @Mock
    MemoryRecallScopeResolver resolver;
    @Mock
    MemoryTagAggregator aggregator;
    @Mock
    MemoryTagSelector selector;
    @Mock
    MemorySummaryReader reader;
    @Mock
    MemoryTurnPatcher patcher;
    @Mock
    MemoryEntryRecallService entryRecallService;
    @Mock
    MemoryTagMapper tagMapper;

    private MemoryRecallPipeline pipeline;

    private static final Long SELF = 1L;
    private static final Long OTHER = 2L;
    private static final String QUERY = "最近爱好啥";

    @BeforeEach
    void setUp() {
        pipeline = new MemoryRecallPipeline(resolver, aggregator, selector, reader, patcher,
                entryRecallService, tagMapper);
        // ①.5 条目合流默认无条目（各条目用例自行覆盖）
        lenient().when(entryRecallService.collectActiveEntries(anyList(), anyLong())).thenReturn(List.of());
    }

    // ---------- helpers ----------

    private static RecallTagMeta tag(long id, String subject, String topic, long owner) {
        RecallTagMeta t = new RecallTagMeta();
        t.setId(id);
        t.setSubject(subject);
        t.setTopic(topic);
        t.setLabel(topic);
        t.setOwnerUserId(owner);
        t.setUsageCount(1);
        return t;
    }

    private static MemorySummary summary(long id, long userId, long tagId, String l1, String l2) {
        MemorySummary s = new MemorySummary();
        s.setId(id);
        s.setUserId(userId);
        s.setTagId(tagId);
        s.setL1Summary(l1);
        s.setL2Detail(l2);
        s.setStatus("CLEAN");
        return s;
    }

    private static RecalledSummary recalled(MemorySummary s, boolean includeL2) {
        return new RecalledSummary(s, includeL2);
    }

    private static MemoryTurn turn(long id, long userId, String direction, String raw) {
        MemoryTurn t = new MemoryTurn();
        t.setId(id);
        t.setUserId(userId);
        t.setDirection(direction);
        t.setRawContent(raw);
        t.setGenDone(true);
        return t;
    }

    /** happy path 默认桩：个人 scope + 2 标签 + 1 总结 + 1 turn。 */
    private void stubHappy() {
        when(resolver.resolve(any(), eq(SELF))).thenReturn(RecallScope.defaultPersonalOnly());
        when(aggregator.aggregate(any(), eq(SELF))).thenReturn(List.of(
                tag(10, "我", "爱好", SELF),
                tag(11, "表哥", "居住", SELF)));
        when(selector.select(eq(QUERY), anyList(), eq(SELF))).thenReturn(List.of(
                tag(10, "我", "爱好", SELF),
                tag(11, "表哥", "居住", SELF)));
        when(reader.read(eq(QUERY), anyList(), any(), eq(SELF))).thenReturn(List.of(
                recalled(summary(1, SELF, 10, "喜欢爬山", "周末常去西湖"), true)));
        when(patcher.collectUncovered(any(), eq(SELF))).thenReturn(List.of(
                turn(100, SELF, "INPUT", "用户问天气")));
    }

    // ===== 1 空 scope =====

    @Test
    void emptyScope_returnsEmpty() {
        when(resolver.resolve(any(), eq(SELF))).thenReturn(
                new RecallScope(false, List.of(), RecallDirection.BOTH, RecallTimeWindow.unbounded(), true));
        MemoryRecallResult r = pipeline.recall(QUERY, null, SELF);
        assertEquals("", r.getAssembledText());
        assertEquals(0, r.getSummaryCount());
        assertEquals(0, r.getTurnCount());
        verifyNoInteractions(aggregator, selector, reader, patcher);
    }

    // ===== 2 happy path =====

    @Test
    void happyPath_assemblesSummaryAndTurn() {
        stubHappy();
        MemoryRecallResult r = pipeline.recall(QUERY, new MemoryRecallScopeRequest(), SELF);
        assertEquals(1, r.getSummaryCount());
        assertEquals(1, r.getTurnCount());
        assertEquals(2, r.getSelectedTags().size());
        assertTrue(r.getAssembledText().contains("爱好：喜欢爬山"), "summary 块装配");
        assertTrue(r.getAssembledText().contains("[INPUT] 用户问天气"), "turn 块装配");
        assertFalse(r.isDegraded());
        // 串行顺序：resolve → aggregate → select → read → patch
        verify(aggregator, times(1)).aggregate(any(), eq(SELF));
        verify(selector, times(1)).select(eq(QUERY), anyList(), eq(SELF));
        verify(reader, times(1)).read(eq(QUERY), anyList(), any(), eq(SELF));
        verify(patcher, times(1)).collectUncovered(any(), eq(SELF));
    }

    // ===== 3 owner=self subject='我' 省主体 =====

    @Test
    void ownSubjectMe_omitsSubjectPrefix() {
        when(resolver.resolve(any(), eq(SELF))).thenReturn(RecallScope.defaultPersonalOnly());
        when(aggregator.aggregate(any(), eq(SELF))).thenReturn(List.of(tag(10, "我", "爱好", SELF)));
        when(selector.select(eq(QUERY), anyList(), eq(SELF))).thenReturn(List.of(tag(10, "我", "爱好", SELF)));
        when(reader.read(eq(QUERY), anyList(), any(), eq(SELF))).thenReturn(List.of(
                recalled(summary(1, SELF, 10, "喜欢爬山", null), true)));
        when(patcher.collectUncovered(any(), eq(SELF))).thenReturn(List.of());
        String text = pipeline.recall(QUERY, null, SELF).getAssembledText();
        assertTrue(text.contains("- 爱好：喜欢爬山"), "省主体直接 topic");
        assertFalse(text.contains("我·爱好"), "不留「我·」前缀");
    }

    // ===== 4 owner=self subject='表哥' 保留主体 =====

    @Test
    void ownSubjectOther_keepsSubjectPrefix() {
        when(resolver.resolve(any(), eq(SELF))).thenReturn(RecallScope.defaultPersonalOnly());
        when(aggregator.aggregate(any(), eq(SELF))).thenReturn(List.of(tag(11, "表哥", "居住", SELF)));
        when(selector.select(eq(QUERY), anyList(), eq(SELF))).thenReturn(List.of(tag(11, "表哥", "居住", SELF)));
        when(reader.read(eq(QUERY), anyList(), any(), eq(SELF))).thenReturn(List.of(
                recalled(summary(1, SELF, 11, "住上海", null), true)));
        when(patcher.collectUncovered(any(), eq(SELF))).thenReturn(List.of());
        String text = pipeline.recall(QUERY, null, SELF).getAssembledText();
        assertTrue(text.contains("- 表哥·居住：住上海"), "保留主体");
    }

    // ===== 5 owner≠self subject='我' owner 前缀替代「我」 =====

    @Test
    void otherOwnerSubjectMe_ownerPrefixReplacesMe() {
        when(resolver.resolve(any(), eq(SELF))).thenReturn(
                new RecallScope(false, List.of(10L), RecallDirection.BOTH, RecallTimeWindow.unbounded(), true));
        when(aggregator.aggregate(any(), eq(SELF))).thenReturn(List.of(tag(20, "我", "爱好", OTHER)));
        when(selector.select(eq(QUERY), anyList(), eq(SELF))).thenReturn(List.of(tag(20, "我", "爱好", OTHER)));
        when(reader.read(eq(QUERY), anyList(), any(), eq(SELF))).thenReturn(List.of(
                recalled(summary(2, OTHER, 20, "爱打球", null), true)));
        when(patcher.collectUncovered(any(), eq(SELF))).thenReturn(List.of());
        String text = pipeline.recall(QUERY, null, SELF).getAssembledText();
        assertTrue(text.contains("- user#2·爱好：爱打球"), "owner 前缀替代「我」");
        assertFalse(text.contains("我·爱好"), "不留「我」");
    }

    // ===== 6 owner≠self subject='本人' 双前缀 =====

    @Test
    void otherOwnerSubjectOther_bothPrefix() {
        when(resolver.resolve(any(), eq(SELF))).thenReturn(
                new RecallScope(false, List.of(10L), RecallDirection.BOTH, RecallTimeWindow.unbounded(), true));
        when(aggregator.aggregate(any(), eq(SELF))).thenReturn(List.of(tag(21, "本人", "工作", OTHER)));
        when(selector.select(eq(QUERY), anyList(), eq(SELF))).thenReturn(List.of(tag(21, "本人", "工作", OTHER)));
        when(reader.read(eq(QUERY), anyList(), any(), eq(SELF))).thenReturn(List.of(
                recalled(summary(3, OTHER, 21, "写代码", null), true)));
        when(patcher.collectUncovered(any(), eq(SELF))).thenReturn(List.of());
        String text = pipeline.recall(QUERY, null, SELF).getAssembledText();
        assertTrue(text.contains("- user#2·本人·工作：写代码"), "owner + 主体双前缀");
    }

    // ===== 7 includeL2 true/false =====

    @Test
    void includeL2False_onlyL1() {
        when(resolver.resolve(any(), eq(SELF))).thenReturn(RecallScope.defaultPersonalOnly());
        when(aggregator.aggregate(any(), eq(SELF))).thenReturn(List.of(tag(10, "我", "爱好", SELF)));
        when(selector.select(eq(QUERY), anyList(), eq(SELF))).thenReturn(List.of(tag(10, "我", "爱好", SELF)));
        when(reader.read(eq(QUERY), anyList(), any(), eq(SELF))).thenReturn(List.of(
                recalled(summary(1, SELF, 10, "L1概要", "L2详述"), false)));  // includeL2=false
        when(patcher.collectUncovered(any(), eq(SELF))).thenReturn(List.of());
        String text = pipeline.recall(QUERY, null, SELF).getAssembledText();
        assertTrue(text.contains("L1概要"));
        assertFalse(text.contains("L2详述"), "includeL2=false 不展 L2");
    }

    @Test
    void includeL2True_l1AndL2() {
        when(resolver.resolve(any(), eq(SELF))).thenReturn(RecallScope.defaultPersonalOnly());
        when(aggregator.aggregate(any(), eq(SELF))).thenReturn(List.of(tag(10, "我", "爱好", SELF)));
        when(selector.select(eq(QUERY), anyList(), eq(SELF))).thenReturn(List.of(tag(10, "我", "爱好", SELF)));
        when(reader.read(eq(QUERY), anyList(), any(), eq(SELF))).thenReturn(List.of(
                recalled(summary(1, SELF, 10, "L1概要", "L2详述"), true)));
        when(patcher.collectUncovered(any(), eq(SELF))).thenReturn(List.of());
        String text = pipeline.recall(QUERY, null, SELF).getAssembledText();
        assertTrue(text.contains("L1概要"));
        assertTrue(text.contains("L2详述"), "includeL2=true 展 L2");
    }

    // ===== 8 turn 装配 =====

    @Test
    void turnAssembles_ownerPrefixDirectionRaw() {
        when(resolver.resolve(any(), eq(SELF))).thenReturn(RecallScope.defaultPersonalOnly());
        when(aggregator.aggregate(any(), eq(SELF))).thenReturn(List.of());  // tags 空 → 跳 select/read
        when(patcher.collectUncovered(any(), eq(SELF))).thenReturn(List.of(
                turn(100, SELF, "OUTPUT", "自答天气"),
                turn(101, OTHER, "INPUT", "他问日程")));
        String text = pipeline.recall(QUERY, null, SELF).getAssembledText();
        assertTrue(text.contains("- [OUTPUT] 自答天气"), "自身 turn 无 owner 前缀带方向");
        assertTrue(text.contains("- user#2·[INPUT] 他问日程"), "他人 turn owner 前缀");
    }

    // ===== 9 aggregate 抛 → 降级 =====

    @Test
    void aggregateThrows_degradedTurnsStillFallback() {
        when(resolver.resolve(any(), eq(SELF))).thenReturn(RecallScope.defaultPersonalOnly());
        when(aggregator.aggregate(any(), eq(SELF))).thenThrow(new RuntimeException("db down"));
        when(patcher.collectUncovered(any(), eq(SELF))).thenReturn(List.of(turn(100, SELF, "INPUT", "兜底原文")));
        MemoryRecallResult r = pipeline.recall(QUERY, null, SELF);
        assertTrue(r.isDegraded(), "标记降级");
        assertFalse(r.getNotes().isEmpty(), "收降级明细");
        assertEquals(0, r.getSummaryCount());
        assertEquals(1, r.getTurnCount(), "turns 兜底仍拼");
        assertTrue(r.getAssembledText().contains("兜底原文"));
        // aggregate 挂后 select/read 跳过（tags 空），patch 仍跑
        verifyNoInteractions(selector, reader);
    }

    // ===== 10 read 抛 → 降级 turns 兜底 =====

    @Test
    void readThrows_degradedTurnsFallback() {
        when(resolver.resolve(any(), eq(SELF))).thenReturn(RecallScope.defaultPersonalOnly());
        when(aggregator.aggregate(any(), eq(SELF))).thenReturn(List.of(tag(10, "我", "爱好", SELF)));
        when(selector.select(eq(QUERY), anyList(), eq(SELF))).thenReturn(List.of(tag(10, "我", "爱好", SELF)));
        when(reader.read(eq(QUERY), anyList(), any(), eq(SELF))).thenThrow(new RuntimeException("llm dead"));
        when(patcher.collectUncovered(any(), eq(SELF))).thenReturn(List.of(turn(100, SELF, "INPUT", "原文兜")));
        MemoryRecallResult r = pipeline.recall(QUERY, null, SELF);
        assertTrue(r.isDegraded());
        assertEquals(0, r.getSummaryCount());
        assertEquals(1, r.getTurnCount());
    }

    // ===== 11 select 返空 → read 返空，patch 兜底 =====

    @Test
    void selectedEmpty_readEmpty_patchStillWorks() {
        when(resolver.resolve(any(), eq(SELF))).thenReturn(RecallScope.defaultPersonalOnly());
        when(aggregator.aggregate(any(), eq(SELF))).thenReturn(List.of(tag(10, "我", "爱好", SELF)));
        when(selector.select(eq(QUERY), anyList(), eq(SELF))).thenReturn(List.of());  // 选空
        when(reader.read(eq(QUERY), anyList(), any(), eq(SELF))).thenReturn(List.of());  // tagIds 空 → 空
        when(patcher.collectUncovered(any(), eq(SELF))).thenReturn(List.of(turn(100, SELF, "INPUT", "原文")));
        MemoryRecallResult r = pipeline.recall(QUERY, null, SELF);
        assertEquals(0, r.getSummaryCount());
        assertEquals(1, r.getTurnCount());
        assertFalse(r.isDegraded(), "select 返空非异常不标降级");
    }

    // ===== 12 selector 降级启发式 =====

    @Test
    void selectorDegradedHeuristic_flagsDegraded() {
        when(resolver.resolve(any(), eq(SELF))).thenReturn(RecallScope.defaultPersonalOnly());
        // 31 标签（>COARSE_TOP=30），select 返全集 = 启发式判 selector 降级
        List<RecallTagMeta> many = java.util.stream.LongStream.rangeClosed(1, 31)
                .mapToObj(i -> tag(i, "我", "t" + i, SELF)).toList();
        when(aggregator.aggregate(any(), eq(SELF))).thenReturn(many);
        when(selector.select(eq(QUERY), anyList(), eq(SELF))).thenReturn(many);  // 等量全集
        when(reader.read(eq(QUERY), anyList(), any(), eq(SELF))).thenReturn(List.of());
        when(patcher.collectUncovered(any(), eq(SELF))).thenReturn(List.of());
        MemoryRecallResult r = pipeline.recall(QUERY, null, SELF);
        assertTrue(r.isDegraded(), "selected==tags 且 size>30 → 启发式降级标记");
    }

    // ===== 13 steps 打点齐全 =====

    @Test
    void stepsRecorded_sevenSteps() {
        stubHappy();
        MemoryRecallResult r = pipeline.recall(QUERY, new MemoryRecallScopeRequest(), SELF);
        List<String> names = r.getSteps().stream().map(RecallTraceStep::step).toList();
        assertEquals(List.of("resolve", "aggregate", "entry-merge", "select", "read", "patch", "assemble"), names);
        assertTrue(r.getSteps().stream().allMatch(s -> s.durationMs() >= 0), "耗时非负");
        assertNotNull(r.getTraceId());
    }

    // ===== 14 scope 持久化请求透传 resolver =====

    @Test
    void scopeRequestPassedThrough_toResolver() {
        MemoryRecallScopeRequest req = new MemoryRecallScopeRequest();
        req.setPersonalOn(false);
        req.setProjectIds(List.of(7L));
        when(resolver.resolve(eq(req), eq(SELF))).thenReturn(RecallScope.defaultPersonalOnly());
        when(aggregator.aggregate(any(), eq(SELF))).thenReturn(List.of());  // tags 空 → 跳 select/read
        when(patcher.collectUncovered(any(), eq(SELF))).thenReturn(List.of());
        pipeline.recall(QUERY, req, SELF);
        verify(resolver).resolve(eq(req), eq(SELF));
    }

    // ============================ 记忆二期 P1 · ①.5 条目合流（FR-007） ============================

    private static MemoryProjectEntryVO entry(long id, long authorId, String authorName,
                                              String l1, List<Long> tagIds) {
        return MemoryProjectEntryVO.builder()
                .id(id).projectId(10L).authorUserId(authorId).authorName(authorName)
                .l1Summary(l1).status("ACTIVE").tagIds(tagIds)
                .build();
    }

    /** 项目 scope（含 projectIds=10）。 */
    private static RecallScope projectScope() {
        return new RecallScope(true, List.of(10L), RecallDirection.BOTH, RecallTimeWindow.unbounded(), true);
    }

    // ===== 15 条目标签命中 selected → 【项目记忆】段装配 =====

    @Test
    void entryTagHitSelected_assemblesProjectMemorySection() {
        when(resolver.resolve(any(), eq(SELF))).thenReturn(projectScope());
        when(aggregator.aggregate(any(), eq(SELF))).thenReturn(List.of(tag(10, "我", "爱好", SELF)));
        when(entryRecallService.collectActiveEntries(List.of(10L), SELF)).thenReturn(List.of(
                entry(1, OTHER, "张三", "接口超时阈值定为 3s", List.of(10L))));
        when(selector.select(eq(QUERY), anyList(), eq(SELF))).thenReturn(List.of(tag(10, "我", "爱好", SELF)));
        when(reader.read(eq(QUERY), anyList(), any(), eq(SELF))).thenReturn(List.of());
        when(patcher.collectUncovered(any(), eq(SELF))).thenReturn(List.of());

        MemoryRecallResult r = pipeline.recall(QUERY, null, SELF);

        assertTrue(r.getAssembledText().contains("【项目记忆】"), "含项目记忆段");
        assertTrue(r.getAssembledText().contains("张三·爱好：接口超时阈值定为 3s"), "作者·标签：L1 格式");
        assertFalse(r.isDegraded());
    }

    // ===== 16 条目标签不在 selected → 不拼（③ LLM 选标签天然过滤） =====

    @Test
    void entryTagNotSelected_excludedFromAssemble() {
        when(resolver.resolve(any(), eq(SELF))).thenReturn(projectScope());
        when(aggregator.aggregate(any(), eq(SELF))).thenReturn(List.of(tag(10, "我", "爱好", SELF)));
        // 条目标签 99 不在聚合集 → 并入候选（tagMapper 补 meta），但 ③ 未选中 → 不拼
        when(entryRecallService.collectActiveEntries(List.of(10L), SELF)).thenReturn(List.of(
                entry(1, OTHER, "张三", "不相关条目", List.of(99L))));
        com.superprogrammer.chat.entity.MemoryTag t99 = new com.superprogrammer.chat.entity.MemoryTag();
        t99.setId(99L);
        t99.setLabel("无关");
        when(tagMapper.selectBatchIds(List.of(99L))).thenReturn(List.of(t99));
        when(selector.select(eq(QUERY), anyList(), eq(SELF))).thenReturn(List.of(tag(10, "我", "爱好", SELF)));
        when(reader.read(eq(QUERY), anyList(), any(), eq(SELF))).thenReturn(List.of());
        when(patcher.collectUncovered(any(), eq(SELF))).thenReturn(List.of());

        MemoryRecallResult r = pipeline.recall(QUERY, null, SELF);

        assertFalse(r.getAssembledText().contains("【项目记忆】"), "未选中标签的条目不拼");
        assertFalse(r.getAssembledText().contains("不相关条目"));
    }

    // ===== 17 tags 全空（turns 兜底路径）→ 全部条目照拼 =====

    @Test
    void entryWithEmptyTags_allEntriesAssembled() {
        when(resolver.resolve(any(), eq(SELF))).thenReturn(projectScope());
        when(aggregator.aggregate(any(), eq(SELF))).thenReturn(List.of());  // tags 空 → 跳 select/read
        when(entryRecallService.collectActiveEntries(List.of(10L), SELF)).thenReturn(List.of(
                entry(1, OTHER, "张三", "无标签条目也拼", null)));
        when(patcher.collectUncovered(any(), eq(SELF))).thenReturn(List.of());

        MemoryRecallResult r = pipeline.recall(QUERY, null, SELF);

        assertTrue(r.getAssembledText().contains("【项目记忆】"));
        assertTrue(r.getAssembledText().contains("张三·收录：无标签条目也拼"), "无标签 → 「收录」占位");
    }

    // ===== 18 条目合流抛异常 → 降级不中断，turns 仍兜底 =====

    @Test
    void entryMergeThrows_degradedTurnsStillFallback() {
        when(resolver.resolve(any(), eq(SELF))).thenReturn(projectScope());
        when(aggregator.aggregate(any(), eq(SELF))).thenReturn(List.of());
        when(entryRecallService.collectActiveEntries(List.of(10L), SELF))
                .thenThrow(new RuntimeException("entry db down"));
        when(patcher.collectUncovered(any(), eq(SELF))).thenReturn(List.of(turn(100, SELF, "INPUT", "兜底原文")));

        MemoryRecallResult r = pipeline.recall(QUERY, null, SELF);

        assertTrue(r.isDegraded(), "entry-merge 失败标降级");
        assertTrue(r.getNotes().stream().anyMatch(n -> n.contains("entry-merge")), "notes 收降级明细");
        assertEquals(1, r.getTurnCount(), "turns 兜底不受影响");
        assertTrue(r.getAssembledText().contains("兜底原文"));
    }

    // ===== 19 空 scope → 不触发条目合流 =====

    @Test
    void emptyScope_noEntryMerge() {
        when(resolver.resolve(any(), eq(SELF))).thenReturn(
                new RecallScope(false, List.of(), RecallDirection.BOTH, RecallTimeWindow.unbounded(), true));
        pipeline.recall(QUERY, null, SELF);
        verifyNoInteractions(entryRecallService);
    }

    // ===== 20 selectEntriesForAssemble 纯函数边界 =====

    @Test
    void selectEntriesForAssemble_boundaries() {
        List<MemoryProjectEntryVO> entries = List.of(entry(1, OTHER, "张三", "x", List.of(10L)));
        assertTrue(MemoryRecallPipeline.selectEntriesForAssemble(null, List.of(), List.of()).isEmpty(), "null 条目 → 空");
        assertTrue(MemoryRecallPipeline.selectEntriesForAssemble(List.of(), List.of(), List.of()).isEmpty(), "空条目 → 空");
        // tags 空 → 全拼
        assertEquals(1, MemoryRecallPipeline.selectEntriesForAssemble(entries, List.of(), List.of()).size());
        // tags 非空 + selected 空 → 全滤
        assertTrue(MemoryRecallPipeline.selectEntriesForAssemble(
                entries, List.of(), List.of(tag(10, "我", "爱好", SELF))).isEmpty());
        // tagIds null 的条目在 tags 非空时被滤
        assertTrue(MemoryRecallPipeline.selectEntriesForAssemble(
                List.of(entry(2, OTHER, "张三", "y", null)),
                List.of(tag(10, "我", "爱好", SELF)),
                List.of(tag(10, "我", "爱好", SELF))).isEmpty());
    }
}
