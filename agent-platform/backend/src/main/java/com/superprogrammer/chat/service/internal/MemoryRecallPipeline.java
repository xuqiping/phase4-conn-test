package com.superprogrammer.chat.service.internal;

import com.superprogrammer.chat.dto.MemoryProjectEntryVO;
import com.superprogrammer.chat.dto.MemoryRecallResult;
import com.superprogrammer.chat.dto.MemoryRecallScopeRequest;
import com.superprogrammer.chat.dto.RecallTagMeta;
import com.superprogrammer.chat.dto.RecalledSummary;
import com.superprogrammer.chat.dto.RecallTraceStep;
import com.superprogrammer.chat.entity.MemorySummary;
import com.superprogrammer.chat.entity.MemoryTag;
import com.superprogrammer.chat.entity.MemoryTurn;
import com.superprogrammer.chat.mapper.MemoryTagMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 计划12 · D-6 · 召回主流程编排（总体设计 §3.3 七步 ①⑦ + 运维「每步打点 + LLM 失败降级链」）。
 * <p>
 * 串七步（每步 try-catch 兜意外异常 → {@code notes} + {@code degraded}）：
 * <ol>
 *   <li><b>① resolve</b>：用户勾选 → {@link RecallScope}（{@link MemoryRecallScopeResolver}，向量 2 项目可访问过滤）。</li>
 *   <li><b>② aggregate</b>：scope 内标签去重清单（{@link MemoryTagAggregator}，向量 3/14）。</li>
 *   <li><b>③ select</b>：LLM 选相关子集 T（{@link MemoryTagSelector}，向量 12，最多 1 次 LLM）。
 *       <i>tags 空时跳 select/read（无标签可选 → 直接走 turns 兜底）。</i></li>
 *   <li><b>④⑤ read</b>：本人总结 + reflect 判深读（{@link MemorySummaryReader}，向量 14 恒只读自己，最多 1 次 LLM）。</li>
 *   <li><b>⑥ patch</b>：未覆盖流水账（{@link MemoryTurnPatcher}，allCovered 严格 + 防 N+1）。</li>
 *   <li><b>⑦ assemble</b>：按 subject 聚合打 owner 前缀装配文本（注入对话 prompt）。</li>
 * </ol>
 * <p>
 * <b>降级链</b>：selector/reader 内部已消化 LLM 失败（不抛），本 pipeline 层每步再 try-catch 兜 mapper/意外异常，
 * 命中 → {@code degraded=true} + {@code notes} 收明细，不中断流程（后续步仍跑）。
 * <p>
 * <b>selector 降级启发式</b>：tags &gt; {@link MemoryTagSelector#COARSE_TOP} 且 selected 数 == tags 数（= LLM 全失败用全集）
 * → 判 selector 内部降级，标 {@code degraded}（selector 不暴露降级信号，启发式兜底）。
 * <p>
 * <b>最多 2 次 LLM</b>：select(1) + reflect(1，仅 summary&gt;5 触发)；embed 不计 chat LLM。
 * <p>
 * <b>打点</b>：{@code steps} 每步 {@code (step, durationMs, count, ok)} + {@code traceId} 串全流程日志。
 * metrics 埋点（prometheus）留运维迭代，本迭代 log + 结构化 steps 够可观测。
 *
 * @see MemoryRecallScopeResolver ①
 * @see MemoryTagAggregator ②
 * @see MemoryTagSelector ③
 * @see MemorySummaryReader ④⑤
 * @see MemoryTurnPatcher ⑥
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryRecallPipeline {

    private final MemoryRecallScopeResolver resolver;
    private final MemoryTagAggregator aggregator;
    private final MemoryTagSelector selector;
    private final MemorySummaryReader reader;
    private final MemoryTurnPatcher patcher;
    private final MemoryEntryRecallService entryRecallService;   // 记忆二期 P1 · ①.5 项目条目合流
    private final MemoryTagMapper tagMapper;                     // 条目标签并入 ② 候选用

    /**
     * 召回主流程入口。
     *
     * @param query  用户当前问题（召回 query，选标签 + reflect 判据）
     * @param req    用户 scope 勾选（可 null → 默认 {个人}）
     * @param userId 召回者
     * @return 装配产物 + 打点 + 降级标记
     */
    public MemoryRecallResult recall(String query, MemoryRecallScopeRequest req, Long userId) {
        String traceId = UUID.randomUUID().toString();
        List<RecallTraceStep> steps = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        long tStart = System.nanoTime();
        log.info("recall 开始 traceId={} userId={} query.len={}", traceId, userId, query == null ? 0 : query.length());

        // ① resolve（向量 2 项目可访问过滤）
        RecallScope scope;
        long t0 = System.nanoTime();
        try {
            scope = resolver.resolve(req, userId);
            steps.add(step("resolve", t0, scope.isEmpty() ? 0 : 1, true));
        } catch (Exception e) {
            log.warn("recall traceId={} resolve 失败: {}", traceId, e.getMessage());
            steps.add(step("resolve", t0, 0, false));
            notes.add("resolve 失败: " + e.getMessage());
            return finish("", List.of(), 0, 0, traceId, steps, notes, tStart);
        }
        if (scope.isEmpty()) {
            log.info("recall traceId={} 空 scope（取消全部勾选）→ 空召回", traceId);
            return finish("", List.of(), 0, 0, traceId, steps, notes, tStart);
        }

        // ② aggregate（向量 3/14）
        long t1 = System.nanoTime();
        List<RecallTagMeta> tags;
        try {
            tags = aggregator.aggregate(scope, userId);
            steps.add(step("aggregate", t1, tags.size(), true));
        } catch (Exception e) {
            log.warn("recall traceId={} aggregate 失败: {}", traceId, e.getMessage());
            tags = List.of();
            steps.add(step("aggregate", t1, 0, false));
            notes.add("aggregate 失败: " + e.getMessage());
        }

        // ①.5 项目条目合流（记忆二期 P1 · FR-007）：scope 内项目 ACTIVE 条目（成员=可读，DEPARTED 失读权）。
        // 独立 try/catch 降级跳过，绝不动主干；条目标签并入 ② 候选集（条目蒸馏产物，标签在作者个人库）。
        long t1h = System.nanoTime();
        List<MemoryProjectEntryVO> entries = List.of();
        try {
            List<MemoryProjectEntryVO> collected = entryRecallService.collectActiveEntries(scope.safeProjectIds(), userId);
            entries = collected == null ? List.of() : collected;
            steps.add(step("entry-merge", t1h, entries.size(), true));
        } catch (Exception e) {
            log.warn("recall traceId={} entry-merge 失败: {}", traceId, e.getMessage());
            entries = List.of();
            steps.add(step("entry-merge", t1h, 0, false));
            notes.add("entry-merge 失败: " + e.getMessage());
        }
        if (!entries.isEmpty()) {
            try {
                Set<Long> knownTagIds = tags.stream().map(RecallTagMeta::getId).collect(Collectors.toSet());
                List<Long> extraTagIds = entries.stream()
                        .flatMap(e -> e.getTagIds() == null ? Stream.<Long>empty() : e.getTagIds().stream())
                        .filter(Objects::nonNull).filter(tid -> !knownTagIds.contains(tid))
                        .distinct().toList();
                if (!extraTagIds.isEmpty()) {
                    List<RecallTagMeta> extraMetas = tagMapper.selectBatchIds(extraTagIds).stream()
                            .map(MemoryRecallPipeline::toTagMeta).toList();
                    tags = new ArrayList<>(tags);
                    tags.addAll(extraMetas);
                }
            } catch (Exception e) {
                log.warn("recall traceId={} 条目标签并入失败(降级不并): {}", traceId, e.getMessage());
                notes.add("条目标签并入失败: " + e.getMessage());
            }
        }

        // ③ select + ④⑤ read（tags 空时跳过——无标签可选，直接走 turns 兜底）
        List<RecallTagMeta> selected = List.of();
        List<RecalledSummary> summaries = List.of();
        if (!tags.isEmpty()) {
            // ③ select（向量 12，最多 1 次 LLM）
            long t2 = System.nanoTime();
            try {
                selected = selector.select(query, tags, userId);
                steps.add(step("select", t2, selected.size(), true));
            } catch (Exception e) {
                log.warn("recall traceId={} select 异常: {}", traceId, e.getMessage());
                selected = tags;  // 降级用全集
                steps.add(step("select", t2, selected.size(), false));
                notes.add("select 异常降级用全集: " + e.getMessage());
            }
            // 启发式：selector 内部降级（LLM 全失败用 candidates 全集，selector 不暴露信号）
            if (tags.size() > MemoryTagSelector.COARSE_TOP
                    && selected.size() == tags.size() && !selected.isEmpty()) {
                notes.add("select 启发式降级：selected==候选全集(size=" + tags.size() + ">" + MemoryTagSelector.COARSE_TOP + ")");
            }

            // ④⑤ read（向量 14 恒只读自己，最多 1 次 LLM）
            List<Long> selectedTagIds = selected.stream()
                    .map(RecallTagMeta::getId).filter(Objects::nonNull).toList();
            long t3 = System.nanoTime();
            try {
                summaries = reader.read(query, selectedTagIds, scope, userId);
                steps.add(step("read", t3, summaries.size(), true));
            } catch (Exception e) {
                log.warn("recall traceId={} read 异常: {}", traceId, e.getMessage());
                summaries = List.of();
                steps.add(step("read", t3, 0, false));
                notes.add("read 异常降级走 turns: " + e.getMessage());
            }
        } else {
            // tags 空：仍记录 select/read 打点（count=0 ok=true，非异常）
            steps.add(new RecallTraceStep("select", 0, 0, true));
            steps.add(new RecallTraceStep("read", 0, 0, true));
        }

        // ⑥ patch（allCovered 严格 + 防 N+1）
        long t4 = System.nanoTime();
        List<MemoryTurn> turns;
        try {
            turns = patcher.collectUncovered(scope, userId);
            steps.add(step("patch", t4, turns.size(), true));
        } catch (Exception e) {
            log.warn("recall traceId={} patch 异常: {}", traceId, e.getMessage());
            turns = List.of();
            steps.add(step("patch", t4, 0, false));
            notes.add("patch 异常: " + e.getMessage());
        }

        // ⑦ assemble（按 subject 聚合打 owner 前缀 + 项目条目打作者前缀）
        long t5 = System.nanoTime();
        List<MemoryProjectEntryVO> entriesToAssemble = selectEntriesForAssemble(entries, selected, tags);
        String assembledText = assemble(summaries, turns, selected, userId, entriesToAssemble);
        steps.add(step("assemble", t5, summaries.size() + turns.size() + entriesToAssemble.size(), true));

        // 二期 P1：turns 纯个人域（召回 turns 恒本人），I3「已离开人员」标注随项目 turns 召回消亡下线
        return finish(assembledText, selected, summaries.size(), turns.size(), traceId, steps, notes, tStart);
    }

    // ============================ 记忆二期 P1 · ①.5/⑥ 条目合流助手 ============================

    /** MemoryTag → RecallTagMeta（条目标签并入候选集用）。 */
    private static RecallTagMeta toTagMeta(MemoryTag t) {
        RecallTagMeta m = new RecallTagMeta();
        m.setId(t.getId());
        m.setSubject(t.getSubject());
        m.setTopic(t.getTopic());
        m.setLabel(t.getLabel());
        m.setOwnerUserId(t.getUserId());
        m.setUsageCount(t.getUsageCount());
        return m;
    }

    /**
     * ⑥ 条目拼入筛选（P4 前条目无 coverage，恒拼 L1——但走标签流）：
     * tags 全空（无标签可选，turns 兜底路径）→ 全部已收集条目都拼；
     * 否则只拼 tag_ids ∩ selected 非空的条目（③ LLM 选标签天然过滤不相关条目）。
     */
    static List<MemoryProjectEntryVO> selectEntriesForAssemble(List<MemoryProjectEntryVO> entries,
                                                               List<RecallTagMeta> selected,
                                                               List<RecallTagMeta> tags) {
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        if (tags == null || tags.isEmpty()) {
            return entries;
        }
        Set<Long> selectedIds = (selected == null ? List.<RecallTagMeta>of() : selected).stream()
                .map(RecallTagMeta::getId).filter(Objects::nonNull).collect(Collectors.toSet());
        return entries.stream()
                .filter(e -> e.getTagIds() != null && e.getTagIds().stream().anyMatch(selectedIds::contains))
                .toList();
    }

    // ============================ ⑦ 装配 ============================

    /**
     * 装配召回文本（注入 prompt）。
     * <p>
     * 行格式：{@code - {owner前缀}{subject前缀}{topic}：{content}}
     * <ul>
     *   <li><b>owner 前缀</b>：{@code owner≠self} 加 {@code user#{id}·}（D-7 前端查用户名美化）。</li>
     *   <li><b>subject 前缀</b>：subject 非 null/空/{@code 我} 加 {@code subject·}（{@code 我} 一律省，
     *       owner≠self 时 owner 名天然替代「我」——设计 §3.3 line 110「subject='我' owner≠当前用户省主体【张三·爱好】」）。</li>
     * </ul>
     * summary content：{@code includeL2=true} 展 L1+L2，否则只 L1；turn 无 subject/topic（多 tag 难归属），
     * 直接 {@code - {owner?}[{direction}] {rawContent}}。
     */
    /** 旧签名兼容（无项目条目段）——委托五参版。 */
    String assemble(List<RecalledSummary> summaries, List<MemoryTurn> turns,
                    List<RecallTagMeta> selectedTags, Long userId) {
        return assemble(summaries, turns, selectedTags, userId, null);
    }

    /**
     * 装配召回文本（注入 prompt）。
     * <p>
     * 行格式：{@code - {owner前缀}{subject前缀}{topic}：{content}}
     * <ul>
     *   <li><b>owner 前缀</b>：{@code owner≠self} 加 {@code user#{id}·}（D-7 前端查用户名美化）。</li>
     *   <li><b>subject 前缀</b>：subject 非 null/空/{@code 我} 加 {@code subject·}（{@code 我} 一律省，
     *       owner≠self 时 owner 名天然替代「我」——设计 §3.3 line 110「subject='我' owner≠当前用户省主体【张三·爱好】」）。</li>
     * </ul>
     * 项目条目段（记忆二期 P1 · FR-007）：{@code 【项目记忆】 - 作者名·标签：蒸馏L1}（条目已脱敏，
     * 作者名来自 users join；标签取条目首个命中选中集的 label）。
     * summary content：{@code includeL2=true} 展 L1+L2，否则只 L1；turn 无 subject/topic（多 tag 难归属），
     * 直接 {@code - {owner?}[{direction}] {rawContent}}。
     */
    String assemble(List<RecalledSummary> summaries, List<MemoryTurn> turns,
                    List<RecallTagMeta> selectedTags, Long userId, List<MemoryProjectEntryVO> entries) {
        if ((summaries == null || summaries.isEmpty()) && (turns == null || turns.isEmpty())
                && (entries == null || entries.isEmpty())) {
            return "";
        }
        Map<Long, RecallTagMeta> tagMap = (selectedTags == null ? List.<RecallTagMeta>of() : selectedTags).stream()
                .filter(t -> t != null && t.getId() != null)
                .collect(Collectors.toMap(RecallTagMeta::getId, t -> t, (a, b) -> a));

        StringBuilder sb = new StringBuilder();
        if (summaries != null && !summaries.isEmpty()) {
            sb.append("【记忆总结】\n");
            for (RecalledSummary rs : summaries) {
                MemorySummary s = rs.summary();
                RecallTagMeta tag = s.getTagId() == null ? null : tagMap.get(s.getTagId());
                String subject = tag == null ? null : tag.getSubject();
                String topic = tag == null || tag.getTopic() == null ? "" : tag.getTopic();
                sb.append("- ").append(ownerSubjectPrefix(s.getUserId(), subject, userId))
                        .append(topic).append("：").append(summaryContent(rs)).append('\n');
            }
        }
        if (entries != null && !entries.isEmpty()) {
            sb.append("【项目记忆】\n");
            for (MemoryProjectEntryVO e : entries) {
                String author = e.getAuthorName() != null && !e.getAuthorName().isBlank()
                        ? e.getAuthorName() : "user#" + e.getAuthorUserId();
                String tagLabel = "";
                if (e.getTagIds() != null) {
                    tagLabel = e.getTagIds().stream()
                            .map(tagMap::get).filter(Objects::nonNull)
                            .map(RecallTagMeta::getLabel).filter(Objects::nonNull)
                            .findFirst().orElse("");
                }
                sb.append("- ").append(author).append('·')
                        .append(tagLabel.isEmpty() ? "收录" : tagLabel)
                        .append("：").append(e.getL1Summary() == null ? "" : e.getL1Summary()).append('\n');
            }
        }
        if (turns != null && !turns.isEmpty()) {
            sb.append("【对话流水】\n");
            for (MemoryTurn t : turns) {
                String owner = (t.getUserId() != null && !t.getUserId().equals(userId))
                        ? "user#" + t.getUserId() + "·" : "";
                String dir = t.getDirection() == null ? "" : t.getDirection();
                String raw = t.getRawContent() == null ? "" : t.getRawContent();
                sb.append("- ").append(owner).append('[').append(dir).append("] ").append(raw).append('\n');
            }
        }
        return sb.toString().trim();
    }

    /** owner + subject 前缀：owner≠self 加 owner 占位；subject 非「我」/空 加主体。 */
    private String ownerSubjectPrefix(Long owner, String subject, Long userId) {
        StringBuilder p = new StringBuilder();
        if (owner != null && !owner.equals(userId)) {
            p.append("user#").append(owner).append('·');
        }
        if (subject != null && !subject.isBlank() && !"我".equals(subject)) {
            p.append(subject).append('·');
        }
        return p.toString();
    }

    /** summary 文本：includeL2=true 且 L2 非空 → L1+换行+L2；否则只 L1。 */
    private String summaryContent(RecalledSummary rs) {
        MemorySummary s = rs.summary();
        String l1 = s.getL1Summary() == null ? "" : s.getL1Summary();
        if (rs.includeL2() && s.getL2Detail() != null && !s.getL2Detail().isBlank()) {
            return l1 + "\n" + s.getL2Detail();
        }
        return l1;
    }

    // ============================ 打点 ============================

    private RecallTraceStep step(String name, long startNanos, int count, boolean ok) {
        long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
        return new RecallTraceStep(name, durationMs, count, ok);
    }

    private MemoryRecallResult finish(String assembledText, List<RecallTagMeta> selectedTags,
                                      int summaryCount, int turnCount, String traceId,
                                      List<RecallTraceStep> steps, List<String> notes, long tStart) {
        boolean degraded = !notes.isEmpty();
        long totalMs = (System.nanoTime() - tStart) / 1_000_000L;
        log.info("recall 完成 traceId={} summaryCount={} turnCount={} degraded={} notes={} 耗时 {}ms",
                traceId, summaryCount, turnCount, degraded, notes.size(), totalMs);
        return MemoryRecallResult.builder()
                .assembledText(assembledText)
                .selectedTags(selectedTags)
                .summaryCount(summaryCount)
                .turnCount(turnCount)
                .degraded(degraded)
                .notes(notes)
                .traceId(traceId)
                .steps(steps)
                .build();
    }
}
