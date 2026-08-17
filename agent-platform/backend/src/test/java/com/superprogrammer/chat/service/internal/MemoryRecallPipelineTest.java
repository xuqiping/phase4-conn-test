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
    @Mock
    MemoryAssetRecallService assetRecallService;
    @Mock
    com.superprogrammer.system.service.SystemSettingService systemSettingService;

    private MemoryRecallPipeline pipeline;

    private static final Long SELF = 1L;
    private static final Long OTHER = 2L;
    private static final String QUERY = "最近爱好啥";
    private static final String MODEL = "doubao-seed-2.0-code";

    @BeforeEach
    void setUp() {
        pipeline = new MemoryRecallPipeline(resolver, aggregator, selector, reader, patcher,
                entryRecallService, tagMapper, assetRecallService, systemSettingService);
        // ①.5 条目合流默认无条目（各条目用例自行覆盖）
        lenient().when(entryRecallService.collectActiveEntries(anyList(), anyLong())).thenReturn(List.of());
        // ⑥.5 文件记忆默认无命中/无深读（各文件用例自行覆盖）；5x 四轮 U3 门控默认桩：
        // embed 成功（合法维度）+ 门控零命中 + 项目卡门原样放行（门控行为在 MemoryAssetRecallServiceTest 单测）
        lenient().when(assetRecallService.collectFileCards(anyList(), anyLong())).thenReturn(List.of());
        lenient().when(assetRecallService.embedQuery(any(), anyLong()))
                .thenReturn(new float[com.superprogrammer.knowledge.util.HalfVecUtil.DIM]);
        lenient().when(assetRecallService.recallGated(anyList(), any(float[].class)))
                .thenReturn(MemoryAssetRecallService.GatedFileRecall.EMPTY);
        lenient().when(assetRecallService.gateProjectCards(anyList(), any(float[].class)))
                .thenAnswer(inv -> inv.getArgument(0));
        // 5x 四轮 C5：附件召回开关默认开（无附件轮不触发，lenient 防闲置告警）
        lenient().when(systemSettingService.isAttachmentRecallEnabled()).thenReturn(true);
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
        when(selector.select(eq(QUERY), anyList(), eq(SELF), any())).thenReturn(List.of(
                tag(10, "我", "爱好", SELF),
                tag(11, "表哥", "居住", SELF)));
        when(reader.read(eq(QUERY), anyList(), any(), eq(SELF), any())).thenReturn(List.of(
                recalled(summary(1, SELF, 10, "喜欢爬山", "周末常去西湖"), true)));
        when(patcher.collectUncovered(any(), eq(SELF))).thenReturn(List.of(
                turn(100, SELF, "INPUT", "用户问天气")));
    }

    // ===== 1 空 scope =====

    @Test
    void emptyScope_returnsEmpty() {
        when(resolver.resolve(any(), eq(SELF))).thenReturn(
                new RecallScope(false, List.of(), RecallDirection.BOTH, RecallTimeWindow.unbounded(), true));
        MemoryRecallResult r = pipeline.recall(QUERY, null, SELF, MODEL);
        assertEquals("", r.getAssembledText());
        assertEquals(0, r.getSummaryCount());
        assertEquals(0, r.getTurnCount());
        verifyNoInteractions(aggregator, selector, reader, patcher);
    }

    // ===== 2 happy path =====

    @Test
    void happyPath_assemblesSummaryAndTurn() {
        stubHappy();
        MemoryRecallResult r = pipeline.recall(QUERY, new MemoryRecallScopeRequest(), SELF, MODEL);
        assertEquals(1, r.getSummaryCount());
        assertEquals(1, r.getTurnCount());
        assertEquals(2, r.getSelectedTags().size());
        assertTrue(r.getAssembledText().contains("爱好：喜欢爬山"), "summary 块装配");
        assertTrue(r.getAssembledText().contains("[INPUT] 用户问天气"), "turn 块装配");
        assertFalse(r.isDegraded());
        // 串行顺序：resolve → aggregate → select → read → patch
        verify(aggregator, times(1)).aggregate(any(), eq(SELF));
        verify(selector, times(1)).select(eq(QUERY), anyList(), eq(SELF), any());
        verify(reader, times(1)).read(eq(QUERY), anyList(), any(), eq(SELF), any());
        verify(patcher, times(1)).collectUncovered(any(), eq(SELF));
    }

    // ===== 3 owner=self subject='我' 省主体 =====

    @Test
    void ownSubjectMe_omitsSubjectPrefix() {
        when(resolver.resolve(any(), eq(SELF))).thenReturn(RecallScope.defaultPersonalOnly());
        when(aggregator.aggregate(any(), eq(SELF))).thenReturn(List.of(tag(10, "我", "爱好", SELF)));
        when(selector.select(eq(QUERY), anyList(), eq(SELF), any())).thenReturn(List.of(tag(10, "我", "爱好", SELF)));
        when(reader.read(eq(QUERY), anyList(), any(), eq(SELF), any())).thenReturn(List.of(
                recalled(summary(1, SELF, 10, "喜欢爬山", null), true)));
        when(patcher.collectUncovered(any(), eq(SELF))).thenReturn(List.of());
        String text = pipeline.recall(QUERY, null, SELF, MODEL).getAssembledText();
        assertTrue(text.contains("- 爱好：喜欢爬山"), "省主体直接 topic");
        assertFalse(text.contains("我·爱好"), "不留「我·」前缀");
    }

    // ===== 4 owner=self subject='表哥' 保留主体 =====

    @Test
    void ownSubjectOther_keepsSubjectPrefix() {
        when(resolver.resolve(any(), eq(SELF))).thenReturn(RecallScope.defaultPersonalOnly());
        when(aggregator.aggregate(any(), eq(SELF))).thenReturn(List.of(tag(11, "表哥", "居住", SELF)));
        when(selector.select(eq(QUERY), anyList(), eq(SELF), any())).thenReturn(List.of(tag(11, "表哥", "居住", SELF)));
        when(reader.read(eq(QUERY), anyList(), any(), eq(SELF), any())).thenReturn(List.of(
                recalled(summary(1, SELF, 11, "住上海", null), true)));
        when(patcher.collectUncovered(any(), eq(SELF))).thenReturn(List.of());
        String text = pipeline.recall(QUERY, null, SELF, MODEL).getAssembledText();
        assertTrue(text.contains("- 表哥·居住：住上海"), "保留主体");
    }

    // ===== 5 owner≠self subject='我' owner 前缀替代「我」 =====

    @Test
    void otherOwnerSubjectMe_ownerPrefixReplacesMe() {
        when(resolver.resolve(any(), eq(SELF))).thenReturn(
                new RecallScope(false, List.of(10L), RecallDirection.BOTH, RecallTimeWindow.unbounded(), true));
        when(aggregator.aggregate(any(), eq(SELF))).thenReturn(List.of(tag(20, "我", "爱好", OTHER)));
        when(selector.select(eq(QUERY), anyList(), eq(SELF), any())).thenReturn(List.of(tag(20, "我", "爱好", OTHER)));
        when(reader.read(eq(QUERY), anyList(), any(), eq(SELF), any())).thenReturn(List.of(
                recalled(summary(2, OTHER, 20, "爱打球", null), true)));
        when(patcher.collectUncovered(any(), eq(SELF))).thenReturn(List.of());
        String text = pipeline.recall(QUERY, null, SELF, MODEL).getAssembledText();
        assertTrue(text.contains("- user#2·爱好：爱打球"), "owner 前缀替代「我」");
        assertFalse(text.contains("我·爱好"), "不留「我」");
    }

    // ===== 6 owner≠self subject='本人' 双前缀 =====

    @Test
    void otherOwnerSubjectOther_bothPrefix() {
        when(resolver.resolve(any(), eq(SELF))).thenReturn(
                new RecallScope(false, List.of(10L), RecallDirection.BOTH, RecallTimeWindow.unbounded(), true));
        when(aggregator.aggregate(any(), eq(SELF))).thenReturn(List.of(tag(21, "本人", "工作", OTHER)));
        when(selector.select(eq(QUERY), anyList(), eq(SELF), any())).thenReturn(List.of(tag(21, "本人", "工作", OTHER)));
        when(reader.read(eq(QUERY), anyList(), any(), eq(SELF), any())).thenReturn(List.of(
                recalled(summary(3, OTHER, 21, "写代码", null), true)));
        when(patcher.collectUncovered(any(), eq(SELF))).thenReturn(List.of());
        String text = pipeline.recall(QUERY, null, SELF, MODEL).getAssembledText();
        assertTrue(text.contains("- user#2·本人·工作：写代码"), "owner + 主体双前缀");
    }

    // ===== 7 includeL2 true/false =====

    @Test
    void includeL2False_onlyL1() {
        when(resolver.resolve(any(), eq(SELF))).thenReturn(RecallScope.defaultPersonalOnly());
        when(aggregator.aggregate(any(), eq(SELF))).thenReturn(List.of(tag(10, "我", "爱好", SELF)));
        when(selector.select(eq(QUERY), anyList(), eq(SELF), any())).thenReturn(List.of(tag(10, "我", "爱好", SELF)));
        when(reader.read(eq(QUERY), anyList(), any(), eq(SELF), any())).thenReturn(List.of(
                recalled(summary(1, SELF, 10, "L1概要", "L2详述"), false)));  // includeL2=false
        when(patcher.collectUncovered(any(), eq(SELF))).thenReturn(List.of());
        String text = pipeline.recall(QUERY, null, SELF, MODEL).getAssembledText();
        assertTrue(text.contains("L1概要"));
        assertFalse(text.contains("L2详述"), "includeL2=false 不展 L2");
    }

    @Test
    void includeL2True_l1AndL2() {
        when(resolver.resolve(any(), eq(SELF))).thenReturn(RecallScope.defaultPersonalOnly());
        when(aggregator.aggregate(any(), eq(SELF))).thenReturn(List.of(tag(10, "我", "爱好", SELF)));
        when(selector.select(eq(QUERY), anyList(), eq(SELF), any())).thenReturn(List.of(tag(10, "我", "爱好", SELF)));
        when(reader.read(eq(QUERY), anyList(), any(), eq(SELF), any())).thenReturn(List.of(
                recalled(summary(1, SELF, 10, "L1概要", "L2详述"), true)));
        when(patcher.collectUncovered(any(), eq(SELF))).thenReturn(List.of());
        String text = pipeline.recall(QUERY, null, SELF, MODEL).getAssembledText();
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
        String text = pipeline.recall(QUERY, null, SELF, MODEL).getAssembledText();
        assertTrue(text.contains("- [OUTPUT] 自答天气"), "自身 turn 无 owner 前缀带方向");
        assertTrue(text.contains("- user#2·[INPUT] 他问日程"), "他人 turn owner 前缀");
    }

    // ===== 9 aggregate 抛 → 降级 =====

    @Test
    void aggregateThrows_degradedTurnsStillFallback() {
        when(resolver.resolve(any(), eq(SELF))).thenReturn(RecallScope.defaultPersonalOnly());
        when(aggregator.aggregate(any(), eq(SELF))).thenThrow(new RuntimeException("db down"));
        when(patcher.collectUncovered(any(), eq(SELF))).thenReturn(List.of(turn(100, SELF, "INPUT", "兜底原文")));
        MemoryRecallResult r = pipeline.recall(QUERY, null, SELF, MODEL);
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
        when(selector.select(eq(QUERY), anyList(), eq(SELF), any())).thenReturn(List.of(tag(10, "我", "爱好", SELF)));
        when(reader.read(eq(QUERY), anyList(), any(), eq(SELF), any())).thenThrow(new RuntimeException("llm dead"));
        when(patcher.collectUncovered(any(), eq(SELF))).thenReturn(List.of(turn(100, SELF, "INPUT", "原文兜")));
        MemoryRecallResult r = pipeline.recall(QUERY, null, SELF, MODEL);
        assertTrue(r.isDegraded());
        assertEquals(0, r.getSummaryCount());
        assertEquals(1, r.getTurnCount());
    }

    // ===== 11 select 返空 → read 返空，patch 兜底 =====

    @Test
    void selectedEmpty_readEmpty_patchStillWorks() {
        when(resolver.resolve(any(), eq(SELF))).thenReturn(RecallScope.defaultPersonalOnly());
        when(aggregator.aggregate(any(), eq(SELF))).thenReturn(List.of(tag(10, "我", "爱好", SELF)));
        when(selector.select(eq(QUERY), anyList(), eq(SELF), any())).thenReturn(List.of());  // 选空
        when(reader.read(eq(QUERY), anyList(), any(), eq(SELF), any())).thenReturn(List.of());  // tagIds 空 → 空
        when(patcher.collectUncovered(any(), eq(SELF))).thenReturn(List.of(turn(100, SELF, "INPUT", "原文")));
        MemoryRecallResult r = pipeline.recall(QUERY, null, SELF, MODEL);
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
        when(selector.select(eq(QUERY), anyList(), eq(SELF), any())).thenReturn(many);  // 等量全集
        when(reader.read(eq(QUERY), anyList(), any(), eq(SELF), any())).thenReturn(List.of());
        when(patcher.collectUncovered(any(), eq(SELF))).thenReturn(List.of());
        MemoryRecallResult r = pipeline.recall(QUERY, null, SELF, MODEL);
        assertTrue(r.isDegraded(), "selected==tags 且 size>30 → 启发式降级标记");
    }

    // ===== 13 steps 打点齐全 =====

    @Test
    void stepsRecorded_sevenSteps() {
        stubHappy();
        MemoryRecallResult r = pipeline.recall(QUERY, new MemoryRecallScopeRequest(), SELF, MODEL);
        List<String> names = r.getSteps().stream().map(RecallTraceStep::step).toList();
        assertEquals(List.of("resolve", "aggregate", "entry-merge", "select", "read", "patch",
                "file-recall", "file-deepread", "assemble"), names);
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
        pipeline.recall(QUERY, req, SELF, MODEL);
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
        when(selector.select(eq(QUERY), anyList(), eq(SELF), any())).thenReturn(List.of(tag(10, "我", "爱好", SELF)));
        when(reader.read(eq(QUERY), anyList(), any(), eq(SELF), any())).thenReturn(List.of());
        when(patcher.collectUncovered(any(), eq(SELF))).thenReturn(List.of());

        MemoryRecallResult r = pipeline.recall(QUERY, null, SELF, MODEL);

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
        when(selector.select(eq(QUERY), anyList(), eq(SELF), any())).thenReturn(List.of(tag(10, "我", "爱好", SELF)));
        when(reader.read(eq(QUERY), anyList(), any(), eq(SELF), any())).thenReturn(List.of());
        when(patcher.collectUncovered(any(), eq(SELF))).thenReturn(List.of());

        MemoryRecallResult r = pipeline.recall(QUERY, null, SELF, MODEL);

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

        MemoryRecallResult r = pipeline.recall(QUERY, null, SELF, MODEL);

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

        MemoryRecallResult r = pipeline.recall(QUERY, null, SELF, MODEL);

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
        pipeline.recall(QUERY, null, SELF, MODEL);
        verifyNoInteractions(entryRecallService);
    }

    // ===== 19b 二期 P2（FR-102）：授权 child 条目装配带「来自授权项目·X」标注 =====
    @Test
    void authorizedChildEntry_assemblesWithSourceMark() {
        when(resolver.resolve(any(), eq(SELF))).thenReturn(projectScope());
        when(aggregator.aggregate(any(), eq(SELF))).thenReturn(List.of(tag(10, "我", "爱好", SELF)));
        MemoryProjectEntryVO childEntry = entry(1, OTHER, "张三", "child 项目的蒸馏条目", List.of(10L));
        childEntry.setViaAuthorizedLink(true);
        childEntry.setProjectName("子项目X");
        when(entryRecallService.collectActiveEntries(List.of(10L), SELF)).thenReturn(List.of(childEntry));
        when(selector.select(eq(QUERY), anyList(), eq(SELF), any())).thenReturn(List.of(tag(10, "我", "爱好", SELF)));
        when(reader.read(eq(QUERY), anyList(), any(), eq(SELF), any())).thenReturn(List.of());
        when(patcher.collectUncovered(any(), eq(SELF))).thenReturn(List.of());

        MemoryRecallResult r = pipeline.recall(QUERY, null, SELF, MODEL);

        assertTrue(r.getAssembledText().contains("来自授权项目·子项目X·张三·爱好：child 项目的蒸馏条目"),
                "授权条目带来源标注");
    }

    // ===== 19c 二期 P4（FR-305）：项目共享总结装配带「项目共享·」来源标注 =====

    @Test
    void projectSharedSummary_assemblesWithSharedMark() {
        when(resolver.resolve(any(), eq(SELF))).thenReturn(projectScope());
        when(aggregator.aggregate(any(), eq(SELF))).thenReturn(List.of(tag(10, "我", "爱好", SELF)));
        when(selector.select(eq(QUERY), anyList(), eq(SELF), any())).thenReturn(List.of(tag(10, "我", "爱好", SELF)));
        MemorySummary shared = new MemorySummary();          // user_id NULL=项目资产
        shared.setId(7L);
        shared.setUserId(null);
        shared.setProjectId(10L);
        shared.setTagId(10L);
        shared.setL1Summary("团队约定接口超时 3s");
        shared.setStatus("CLEAN");
        shared.setScopeOwner("PROJECT");
        when(reader.read(eq(QUERY), anyList(), any(), eq(SELF), any())).thenReturn(List.of(recalled(shared, true)));
        when(patcher.collectUncovered(any(), eq(SELF))).thenReturn(List.of());

        MemoryRecallResult r = pipeline.recall(QUERY, null, SELF, MODEL);

        assertTrue(r.getAssembledText().contains("项目共享·爱好：团队约定接口超时 3s"),
                "共享总结带「项目共享·」标注");
    }

    // ===== 20 selectEntriesForAssemble 纯函数边界 =====

    @Test
    void selectEntriesForAssemble_boundaries() {
        List<MemoryProjectEntryVO> entries = List.of(entry(1, OTHER, "张三", "x", List.of(10L)));
        assertTrue(MemoryRecallPipeline.selectEntriesForAssemble(null, List.of(), List.of()).isEmpty(), "null 条目 → 空");
        assertTrue(MemoryRecallPipeline.selectEntriesForAssemble(List.of(), List.of(), List.of()).isEmpty(), "空条目 → 空");
        // tags 空 → 全拼
        assertEquals(1, MemoryRecallPipeline.selectEntriesForAssemble(entries, List.of(), List.of()).size());
        // tags 非空 + selected 空 → 有标签条目被滤（tag_ids 非空且未命中 selected）
        assertTrue(MemoryRecallPipeline.selectEntriesForAssemble(
                entries, List.of(), List.of(tag(10, "我", "爱好", SELF))).isEmpty());
        // 无标签条目（收录课件/附件）恒拼：tagIds null 或空，即使 scope 有标签源也保留
        assertEquals(1, MemoryRecallPipeline.selectEntriesForAssemble(
                List.of(entry(2, OTHER, "张三", "y", null)),
                List.of(tag(10, "我", "爱好", SELF)),
                List.of(tag(10, "我", "爱好", SELF))).size(), "tagIds null → 恒拼");
        assertEquals(1, MemoryRecallPipeline.selectEntriesForAssemble(
                List.of(entry(3, OTHER, "张三", "z", List.of())),
                List.of(tag(10, "我", "爱好", SELF)),
                List.of(tag(10, "我", "爱好", SELF))).size(), "tagIds 空 → 恒拼");
    }

    // ============================ 记忆二期 P3 · ⑥.5 文件记忆召回+深读（FR-203） ============================

    private static com.superprogrammer.chat.dto.RecalledFileCard fileCard(long memoryId, String name,
                                                                          boolean cleaned) {
        return com.superprogrammer.chat.dto.RecalledFileCard.builder()
                .memoryId(memoryId).fileId("file-" + memoryId).originalName(name)
                .fileKind("PDF").chunkCount(12).weakMemory(false)
                .fileCleaned(cleaned).downloadable(!cleaned)
                .l1("讲了 hooks 基础").l2("第3页讲 useState").build();
    }

    /** ⑥.5 默认桩：个人 scope + 1 标签选中 + read/patch 空（reader/patcher lenient：用例可复写）。 */
    private void stubFileRecallBase() {
        when(resolver.resolve(any(), eq(SELF))).thenReturn(RecallScope.defaultPersonalOnly());
        when(aggregator.aggregate(any(), eq(SELF))).thenReturn(List.of(tag(10, "文件", "hooks", SELF)));
        when(selector.select(eq(QUERY), anyList(), eq(SELF), any())).thenReturn(List.of(tag(10, "文件", "hooks", SELF)));
        lenient().when(reader.read(eq(QUERY), anyList(), any(), eq(SELF), any())).thenReturn(List.of());
        lenient().when(patcher.collectUncovered(any(), eq(SELF))).thenReturn(List.of());
    }

    // ===== 21 文件命中 → 【文件记忆】卡片块 + fileCards 透出（5x 四轮 U3 后经 recallGated 门控）=====

    @Test
    void fileCardHit_assemblesFileMemorySection() {
        stubFileRecallBase();
        java.util.List<com.superprogrammer.chat.dto.RecalledFileCard> cards = List.of(
                fileCard(501, "React课件.pdf", false));
        when(assetRecallService.collectFileCards(List.of(10L), SELF)).thenReturn(cards);
        when(assetRecallService.recallGated(eq(cards), any(float[].class)))
                .thenReturn(new MemoryAssetRecallService.GatedFileRecall(cards, List.of()));

        MemoryRecallResult r = pipeline.recall(QUERY, null, SELF, MODEL);

        assertTrue(r.getAssembledText().contains("【文件记忆】"), "含文件记忆段");
        assertTrue(r.getAssembledText().contains("《React课件.pdf》（PDF 文档·共12块·可下载·file:file-501）：讲了 hooks 基础"),
                "卡片行含名称/类型/块数/可下载/fileId/l1");
        assertTrue(r.getAssembledText().contains("第3页讲 useState"), "l2 换行续接");
        assertEquals(1, r.getFileCards().size(), "fileCards 透出（Step5 前端卡片数据源）");
        assertFalse(r.isDegraded());
    }

    // ===== 22 深读命中 → 【文件深读】块带 pageRef =====

    @Test
    void deepReadHit_assemblesPageRefChunks() {
        stubFileRecallBase();
        java.util.List<com.superprogrammer.chat.dto.RecalledFileCard> cards = List.of(
                fileCard(501, "React课件.pdf", false));
        when(assetRecallService.collectFileCards(List.of(10L), SELF)).thenReturn(cards);
        when(assetRecallService.recallGated(eq(cards), any(float[].class)))
                .thenReturn(new MemoryAssetRecallService.GatedFileRecall(cards, List.of(
                        new MemoryAssetRecallService.DeepReadChunk(501L, "React课件.pdf", "第12页", "useEffect 依赖数组规则", 0.21d))));

        MemoryRecallResult r = pipeline.recall(QUERY, null, SELF, MODEL);

        assertTrue(r.getAssembledText().contains("【文件深读】"), "含深读段");
        assertTrue(r.getAssembledText().contains("《React课件.pdf》[第12页]：useEffect 依赖数组规则"),
                "深读行带 pageRef 锚点");
        assertTrue(r.getAssembledText().contains("回答引用须带页码锚点"), "块头明示引用铁律");
    }

    // ===== 23 原文件 CLEANED → 卡片标「原文件已删除」总结仍可召回 =====

    @Test
    void cleanedFile_marksDeletedButKeepsSummary() {
        stubFileRecallBase();
        java.util.List<com.superprogrammer.chat.dto.RecalledFileCard> cards = List.of(
                fileCard(502, "旧课件.pdf", true));
        when(assetRecallService.collectFileCards(List.of(10L), SELF)).thenReturn(cards);
        when(assetRecallService.recallGated(eq(cards), any(float[].class)))
                .thenReturn(new MemoryAssetRecallService.GatedFileRecall(cards, List.of()));

        MemoryRecallResult r = pipeline.recall(QUERY, null, SELF, MODEL);

        assertTrue(r.getAssembledText().contains("《旧课件.pdf》（PDF 文档·共12块·原文件已删除·file:file-502）"),
                "CLEANED 标原文件已删除");
        assertTrue(r.getAssembledText().contains("讲了 hooks 基础"), "总结仍可召回");
    }

    // ===== 24 个人域关闭（personalOn=false）→ 不查文件记忆 =====

    @Test
    void personalOff_skipsFileRecall() {
        when(resolver.resolve(any(), eq(SELF))).thenReturn(
                new RecallScope(false, List.of(10L), RecallDirection.BOTH, RecallTimeWindow.unbounded(), true));
        when(aggregator.aggregate(any(), eq(SELF))).thenReturn(List.of(tag(10, "我", "爱好", SELF)));
        when(selector.select(eq(QUERY), anyList(), eq(SELF), any())).thenReturn(List.of(tag(10, "我", "爱好", SELF)));
        when(reader.read(eq(QUERY), anyList(), any(), eq(SELF), any())).thenReturn(List.of());
        when(patcher.collectUncovered(any(), eq(SELF))).thenReturn(List.of());

        pipeline.recall(QUERY, null, SELF, MODEL);

        verifyNoInteractions(assetRecallService);
    }

    // ===== 25 file-recall 抛异常 → 降级不中断，turns 仍兜底 =====

    @Test
    void fileRecallThrows_degradedTurnsFallback() {
        stubFileRecallBase();
        when(assetRecallService.collectFileCards(anyList(), eq(SELF)))
                .thenThrow(new RuntimeException("asset db down"));
        when(patcher.collectUncovered(any(), eq(SELF))).thenReturn(List.of(turn(100, SELF, "INPUT", "兜底原文")));

        MemoryRecallResult r = pipeline.recall(QUERY, null, SELF, MODEL);

        assertTrue(r.isDegraded(), "file-recall 失败标降级");
        assertTrue(r.getNotes().stream().anyMatch(n -> n.contains("file-recall")), "notes 收明细");
        assertTrue(r.getAssembledText().contains("兜底原文"), "turns 兜底不受影响");
        assertFalse(r.getAssembledText().contains("【文件记忆】"));
    }

    // ===== 26 tags 全空（无标签可选）→ 不查文件记忆但打点齐 =====

    @Test
    void emptyTags_skipsFileRecallButStepsRecorded() {
        when(resolver.resolve(any(), eq(SELF))).thenReturn(RecallScope.defaultPersonalOnly());
        when(aggregator.aggregate(any(), eq(SELF))).thenReturn(List.of());
        when(patcher.collectUncovered(any(), eq(SELF))).thenReturn(List.of());

        MemoryRecallResult r = pipeline.recall(QUERY, null, SELF, MODEL);

        verifyNoInteractions(assetRecallService);
        List<String> names = r.getSteps().stream().map(RecallTraceStep::step).toList();
        assertTrue(names.contains("file-recall") && names.contains("file-deepread"), "零命中也打点");
    }

    // ============================ 记忆二期 P3 扩展 · 项目收录附件下载卡片（FR-204+） ============================

    /** 项目 FILE 条目 → 召回装配后产出下载卡片（成员可下载，memoryId=null 不展开分块）。 */
    private static com.superprogrammer.chat.dto.RecalledFileCard projectFileCard(String fileId, String name,
                                                                                  boolean cleaned) {
        return com.superprogrammer.chat.dto.RecalledFileCard.builder()
                .memoryId(null).fileId(fileId).originalName(name)
                .fileKind("PDF").chunkCount(0).weakMemory(false)
                .fileCleaned(cleaned).downloadable(!cleaned)
                .l1("项目蒸馏 L1").build();
    }

    // ===== 27 项目收录附件（FILE 条目）→ 【项目记忆】标注可下载 + fileCards 透出下载卡片 =====

    @Test
    void projectFileEntry_emitsDownloadCardAndHint() {
        when(resolver.resolve(any(), eq(SELF))).thenReturn(projectScope());
        when(aggregator.aggregate(any(), eq(SELF))).thenReturn(List.of(tag(10, "我", "课件", SELF)));
        MemoryProjectEntryVO fileEntry = MemoryProjectEntryVO.builder()
                .id(7L).projectId(10L).authorUserId(OTHER).authorName("张三")
                .l1Summary("hooks 课件讲义").contentType("FILE").fileId("f-courseware")
                .tagIds(List.of(10L)).build();
        when(entryRecallService.collectActiveEntries(List.of(10L), SELF)).thenReturn(List.of(fileEntry));
        when(selector.select(eq(QUERY), anyList(), eq(SELF), any())).thenReturn(List.of(tag(10, "我", "课件", SELF)));
        when(reader.read(eq(QUERY), anyList(), any(), eq(SELF), any())).thenReturn(List.of());
        when(patcher.collectUncovered(any(), eq(SELF))).thenReturn(List.of());
        when(assetRecallService.collectFileCardsForEntries(anyList())).thenReturn(List.of(
                projectFileCard("f-courseware", "React课件.pdf", false)));

        MemoryRecallResult r = pipeline.recall(QUERY, null, SELF, MODEL);

        // 【项目记忆】段：FILE 条目行尾标注附件可下载 + fileId（LLM 据此告知用户可下载）
        assertTrue(r.getAssembledText().contains("【项目记忆】"));
        assertTrue(r.getAssembledText().contains(
                "张三·课件：hooks 课件讲义（附件《React课件.pdf》可下载·file:f-courseware）"),
                "FILE 条目行尾标注附件可下载+fileId");
        // 项目卡片透出（前端 MessageFileCard 据此渲染下载按钮）
        assertEquals(1, r.getFileCards().size(), "项目文件卡片透出");
        assertEquals("f-courseware", r.getFileCards().get(0).getFileId());
        assertNull(r.getFileCards().get(0).getMemoryId(), "项目卡片 memoryId=null（不展开分块，仅下载）");
        assertEquals(0, r.getFileCards().get(0).getChunkCount(), "项目卡片 chunkCount=0（前端隐藏展开分块按钮）");
        // 项目卡片不进【文件记忆】文本块（已在【项目记忆】标注，避免重复）
        assertFalse(r.getAssembledText().contains("【文件记忆】"), "项目卡片不重复入文件记忆块");
    }

    // ===== 28 项目附件原文件已删除（CLEANED）→ 标注「原文件已删除」、卡片不可下载 =====

    @Test
    void projectFileEntry_cleaned_marksDeleted() {
        when(resolver.resolve(any(), eq(SELF))).thenReturn(projectScope());
        when(aggregator.aggregate(any(), eq(SELF))).thenReturn(List.of(tag(10, "我", "课件", SELF)));
        MemoryProjectEntryVO fileEntry = MemoryProjectEntryVO.builder()
                .id(8L).projectId(10L).authorUserId(OTHER).authorName("李四")
                .l1Summary("已删课件").contentType("FILE").fileId("f-gone")
                .tagIds(List.of(10L)).build();
        when(entryRecallService.collectActiveEntries(List.of(10L), SELF)).thenReturn(List.of(fileEntry));
        when(selector.select(eq(QUERY), anyList(), eq(SELF), any())).thenReturn(List.of(tag(10, "我", "课件", SELF)));
        when(reader.read(eq(QUERY), anyList(), any(), eq(SELF), any())).thenReturn(List.of());
        when(patcher.collectUncovered(any(), eq(SELF))).thenReturn(List.of());
        when(assetRecallService.collectFileCardsForEntries(anyList())).thenReturn(List.of(
                projectFileCard("f-gone", "旧课件.pdf", true)));

        MemoryRecallResult r = pipeline.recall(QUERY, null, SELF, MODEL);

        assertTrue(r.getAssembledText().contains("李四·课件：已删课件（附件《旧课件.pdf》原文件已删除）"),
                "CLEANED 标原文件已删除");
        assertTrue(r.getFileCards().get(0).isFileCleaned(), "卡片标已删除");
        assertFalse(r.getFileCards().get(0).isDownloadable(), "不可下载");
    }

    // ===== 29 项目文件卡片与个人文件记忆按 fileId 去重（同一文件已是本人记忆则不重复出项目卡） =====

    @Test
    void projectFileCard_dedupsWithPersonalCardByFileId() {
        when(resolver.resolve(any(), eq(SELF))).thenReturn(projectScope());  // personalOn=true + 项目 10
        when(aggregator.aggregate(any(), eq(SELF))).thenReturn(List.of(tag(10, "我", "课件", SELF)));
        when(selector.select(eq(QUERY), anyList(), eq(SELF), any())).thenReturn(List.of(tag(10, "我", "课件", SELF)));
        when(reader.read(eq(QUERY), anyList(), any(), eq(SELF), any())).thenReturn(List.of());
        when(patcher.collectUncovered(any(), eq(SELF))).thenReturn(List.of());
        // 个人文件记忆命中 fileId=file-501（memoryId=501，有展开分块）
        java.util.List<com.superprogrammer.chat.dto.RecalledFileCard> personal = List.of(
                fileCard(501, "React课件.pdf", false));
        when(assetRecallService.collectFileCards(List.of(10L), SELF)).thenReturn(personal);
        when(assetRecallService.recallGated(eq(personal), any(float[].class)))
                .thenReturn(new MemoryAssetRecallService.GatedFileRecall(personal, List.of()));
        // 项目条目同 fileId → collectFileCardsForEntries 返项目卡，但应被去重剔除
        MemoryProjectEntryVO fileEntry = MemoryProjectEntryVO.builder()
                .id(9L).projectId(10L).authorUserId(OTHER).authorName("张三")
                .l1Summary("同文件项目收录").contentType("FILE").fileId("file-501")
                .tagIds(List.of(10L)).build();
        when(entryRecallService.collectActiveEntries(List.of(10L), SELF)).thenReturn(List.of(fileEntry));
        when(assetRecallService.collectFileCardsForEntries(anyList())).thenReturn(List.of(
                projectFileCard("file-501", "React课件.pdf", false)));

        MemoryRecallResult r = pipeline.recall(QUERY, null, SELF, MODEL);

        // 仅 1 张卡片（个人卡优先，项目卡同 fileId 去重）
        assertEquals(1, r.getFileCards().size(), "同 fileId 去重：仅 1 张卡片");
        assertEquals(501L, r.getFileCards().get(0).getMemoryId(), "保留个人卡（有展开分块），项目卡被剔");
    }

    // ============================ 5x 四轮 U3 · 文件召回向量门控 ============================

    /** select 异常降级（selected=全集）→ 跳过文件召回（宁缺勿噪，U3 刷屏放大器封口）。 */
    @Test
    void selectDegraded_skipsFileRecall() {
        when(resolver.resolve(any(), eq(SELF))).thenReturn(RecallScope.defaultPersonalOnly());
        when(aggregator.aggregate(any(), eq(SELF))).thenReturn(List.of(tag(10, "文件", "hooks", SELF)));
        when(selector.select(eq(QUERY), anyList(), eq(SELF), any())).thenThrow(new RuntimeException("llm down"));
        lenient().when(reader.read(eq(QUERY), anyList(), any(), eq(SELF), any())).thenReturn(List.of());
        lenient().when(patcher.collectUncovered(any(), eq(SELF))).thenReturn(List.of());

        MemoryRecallResult r = pipeline.recall(QUERY, null, SELF, MODEL);

        verify(assetRecallService, never()).collectFileCards(anyList(), anyLong());
        assertTrue(r.getNotes().stream().anyMatch(n -> n.contains("select 降级轮：跳过文件召回")),
                "note 记跳过原因");
        assertFalse(r.getAssembledText().contains("【文件记忆】"));
    }

    /** select 启发式降级（selected==候选全集且 size>COARSE_TOP）→ 同样跳过文件召回。 */
    @Test
    void selectHeuristicDegraded_skipsFileRecall() {
        when(resolver.resolve(any(), eq(SELF))).thenReturn(RecallScope.defaultPersonalOnly());
        List<RecallTagMeta> many = new java.util.ArrayList<>();
        for (long i = 1; i <= MemoryTagSelector.COARSE_TOP + 1; i++) {
            many.add(tag(i, "我", "话题" + i, SELF));
        }
        when(aggregator.aggregate(any(), eq(SELF))).thenReturn(many);
        when(selector.select(eq(QUERY), anyList(), eq(SELF), any())).thenReturn(many);  // 全集 = 降级
        lenient().when(reader.read(eq(QUERY), anyList(), any(), eq(SELF), any())).thenReturn(List.of());
        lenient().when(patcher.collectUncovered(any(), eq(SELF))).thenReturn(List.of());

        MemoryRecallResult r = pipeline.recall(QUERY, null, SELF, MODEL);

        verify(assetRecallService, never()).collectFileCards(anyList(), anyLong());
        assertTrue(r.getNotes().stream().anyMatch(n -> n.contains("select 降级轮：跳过文件召回")));
    }

    /** query embed 失败 → 零文件卡零深读，note 记降级（宁缺勿噪），非 degraded 主干。 */
    @Test
    void embedFails_zeroFileCardsWithNote() {
        stubFileRecallBase();
        when(assetRecallService.collectFileCards(List.of(10L), SELF)).thenReturn(List.of(
                fileCard(501, "React课件.pdf", false)));
        when(assetRecallService.embedQuery(eq(QUERY), eq(SELF))).thenReturn(null);

        MemoryRecallResult r = pipeline.recall(QUERY, null, SELF, MODEL);

        assertTrue(r.getFileCards().isEmpty(), "embed 失败零文件卡");
        assertTrue(r.getNotes().stream().anyMatch(n -> n.contains("query embed 失败：零文件卡")));
        assertFalse(r.getAssembledText().contains("【文件记忆】"));
    }

    /** 项目附件卡过 gateProjectCards 同门（被门掉则不透出）。 */
    @Test
    void projectFileCard_gatedByVector() {
        when(resolver.resolve(any(), eq(SELF))).thenReturn(projectScope());
        when(aggregator.aggregate(any(), eq(SELF))).thenReturn(List.of(tag(10, "我", "课件", SELF)));
        MemoryProjectEntryVO fileEntry = MemoryProjectEntryVO.builder()
                .id(7L).projectId(10L).authorUserId(OTHER).authorName("张三")
                .l1Summary("hooks 课件讲义").contentType("FILE").fileId("f-courseware")
                .tagIds(List.of(10L)).build();
        when(entryRecallService.collectActiveEntries(List.of(10L), SELF)).thenReturn(List.of(fileEntry));
        when(selector.select(eq(QUERY), anyList(), eq(SELF), any())).thenReturn(List.of(tag(10, "我", "课件", SELF)));
        lenient().when(reader.read(eq(QUERY), anyList(), any(), eq(SELF), any())).thenReturn(List.of());
        lenient().when(patcher.collectUncovered(any(), eq(SELF))).thenReturn(List.of());
        when(assetRecallService.collectFileCardsForEntries(anyList())).thenReturn(List.of(
                projectFileCard("f-courseware", "React课件.pdf", false)));
        // 门控判不相关 → 空列表
        when(assetRecallService.gateProjectCards(anyList(), any(float[].class))).thenReturn(List.of());

        MemoryRecallResult r = pipeline.recall(QUERY, null, SELF, MODEL);

        assertTrue(r.getFileCards().isEmpty(), "被门掉的项目卡不透出");
        assertFalse(r.getAssembledText().contains("【文件记忆】"));
    }

    // ============================ 5x 四轮 U8（C5）· 附件定向召回 ============================

    /** 附件注入：attached 卡置顶 + 同 fileId 门控卡去重 + 附件开头块插最前（非 READY 状态标透传）。 */
    @Test
    void attachmentRecall_injectsAndToppesCards() {
        stubFileRecallBase();
        // 标签召回链同文件命中（门控过阈卡 memoryId=501 + 深读块）
        java.util.List<com.superprogrammer.chat.dto.RecalledFileCard> gated = List.of(
                fileCard(501, "React课件.pdf", false));
        when(assetRecallService.collectFileCards(List.of(10L), SELF)).thenReturn(gated);
        when(assetRecallService.recallGated(eq(gated), any(float[].class)))
                .thenReturn(new MemoryAssetRecallService.GatedFileRecall(gated, List.of(
                        new MemoryAssetRecallService.DeepReadChunk(501L, "React课件.pdf", "第12页", "近邻深读块", 0.2d))));
        // 附件段：READY 附件 A（file-501 同文件）+ PROCESSING 附件 B
        com.superprogrammer.chat.dto.RecalledFileCard attachA =
                com.superprogrammer.chat.dto.RecalledFileCard.builder()
                        .memoryId(501L).fileId("file-501").originalName("React课件.pdf")
                        .fileKind("PDF").chunkCount(0).attached(true).fileCleaned(false)
                        .downloadable(true).l1("附件卡l1").build();
        com.superprogrammer.chat.dto.RecalledFileCard attachB =
                com.superprogrammer.chat.dto.RecalledFileCard.builder()
                        .memoryId(502L).fileId("file-502").originalName("解析中.pdf")
                        .fileKind("PDF").chunkCount(0).attached(true)
                        .attachStatus("PROCESSING").fileCleaned(false)
                        .downloadable(true).l1("附件卡l1-b").build();
        when(assetRecallService.recallAttachments(List.of("file-501", "file-502"), SELF))
                .thenReturn(new MemoryAssetRecallService.AttachmentRecall(
                        List.of(attachA, attachB),
                        List.of(new MemoryAssetRecallService.DeepReadChunk(501L, "React课件.pdf", "第1页", "附件开篇块", 0d))));

        MemoryRecallResult r = pipeline.recall(QUERY, null, SELF, MODEL, List.of("file-501", "file-502"));

        assertEquals(2, r.getFileCards().size(), "附件A(覆盖同fileId门控卡) + 附件B");
        assertTrue(r.getFileCards().get(0).getAttached(), "附件卡置顶（attached 优先）");
        assertEquals("file-501", r.getFileCards().get(0).getFileId());
        assertTrue(Boolean.TRUE.equals(r.getFileCards().get(1).getAttached()));
        assertEquals("PROCESSING", r.getFileCards().get(1).getAttachStatus(), "非 READY 状态标透传（前端解析中 chip）");
        assertTrue(r.getAssembledText().contains("【文件记忆】"), "附件卡进文件记忆块");
        assertTrue(r.getAssembledText().contains("附件开篇块"), "附件开头块注入（免阈值）");
        assertFalse(r.getAssembledText().contains("近邻深读块"),
                "同记忆深读块去重（附件开头块已注入，不重复文本）");
        assertTrue(r.getSteps().stream().anyMatch(s -> "attach-recall".equals(s.step()) && s.count() == 2),
                "attach-recall 打点 count=2");
        assertFalse(r.isDegraded(), "附件正常注入非降级");
    }

    /** 开关关（rag.memory.attachment-recall.enabled=false）→ 跳过附件段，标签召回链不受影响。 */
    @Test
    void attachmentRecall_switchOff_skipsAttachmentSection() {
        when(systemSettingService.isAttachmentRecallEnabled()).thenReturn(false);
        stubFileRecallBase();
        when(patcher.collectUncovered(any(), eq(SELF))).thenReturn(List.of());

        MemoryRecallResult r = pipeline.recall(QUERY, null, SELF, MODEL, List.of("file-501"));

        verify(assetRecallService, never()).recallAttachments(anyList(), anyLong());
        assertTrue(r.getNotes().isEmpty(), "配置性跳过不打 notes（不误标 degraded）");
        assertTrue(r.getFileCards().isEmpty());
        assertFalse(r.isDegraded());
    }

    /** 附件段异常 → fail-open 按无附件处理（note 记明细），主干标签召回/turns 兜底不受影响。 */
    @Test
    void attachmentRecall_throws_failOpen() {
        stubFileRecallBase();
        when(assetRecallService.recallAttachments(anyList(), eq(SELF)))
                .thenThrow(new RuntimeException("attach db down"));
        when(patcher.collectUncovered(any(), eq(SELF))).thenReturn(List.of(turn(100, SELF, "INPUT", "兜底原文")));

        MemoryRecallResult r = pipeline.recall(QUERY, null, SELF, MODEL, List.of("file-501"));

        assertTrue(r.isDegraded(), "fail-open 标降级");
        assertTrue(r.getNotes().stream().anyMatch(n -> n.contains("附件召回失败：按无附件处理")));
        assertTrue(r.getAssembledText().contains("兜底原文"), "turns 兜底不受影响");
        assertTrue(r.getFileCards().isEmpty());
    }
}
