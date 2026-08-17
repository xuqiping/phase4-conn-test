package com.superprogrammer.chat.service.internal;

import com.superprogrammer.chat.dto.MemoryProjectEntryVO;
import com.superprogrammer.chat.dto.MemoryRecallResult;
import com.superprogrammer.chat.dto.MemoryRecallScopeRequest;
import com.superprogrammer.chat.dto.RecallTagMeta;
import com.superprogrammer.chat.dto.RecalledFileCard;
import com.superprogrammer.chat.dto.RecalledSummary;
import com.superprogrammer.chat.dto.RecallTraceStep;
import com.superprogrammer.chat.entity.MemoryProjectEntry;
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
 *   <li><b>⑥.5 file-recall/deepread</b>：二期 P3 文件记忆——个人域 READY 文件记忆按 ③ 选中标签命中
 *       进装配（「文件记忆」卡片块），query embed 分块向量 top-5 深读（「文件深读」块带 page_ref）；
 *       仅 personalOn 时召回，独立降级不动主干（{@link MemoryAssetRecallService}）。</li>
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
    private final MemoryAssetRecallService assetRecallService;   // 记忆二期 P3 · ⑥.5 文件记忆召回+深读

    /**
     * 召回主流程入口。
     *
     * @param query  用户当前问题（召回 query，选标签 + reflect 判据）
     * @param req    用户 scope 勾选（可 null → 默认 {个人}）
     * @param userId 召回者
     * @param model  对话所选 model（透传给 select/reflect LLM，null → 各组件回退默认）
     * @return 装配产物 + 打点 + 降级标记
     */
    public MemoryRecallResult recall(String query, MemoryRecallScopeRequest req, Long userId, String model) {
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
            return finish("", List.of(), 0, 0, null, traceId, steps, notes, tStart);
        }
        if (scope.isEmpty()) {
            log.info("recall traceId={} 空 scope（取消全部勾选）→ 空召回", traceId);
            return finish("", List.of(), 0, 0, null, traceId, steps, notes, tStart);
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
        List<Long> selectedTagIds = List.of();   // ⑥.5 文件记忆命中也用（提升作用域）
        // 5x 四轮 U3：select 降级标记（异常兜底全集 / 启发式判全集）——降级轮标签重叠失去相关性
        // 意义，⑥.5 跳过文件召回（全集 ∩ 文件 tag = 全部文件 → 无关文件刷屏的放大器）。
        boolean selectDegraded = false;
        if (!tags.isEmpty()) {
            // ③ select（向量 12，最多 1 次 LLM）
            long t2 = System.nanoTime();
            try {
                selected = selector.select(query, tags, userId, model);
                steps.add(step("select", t2, selected.size(), true));
            } catch (Exception e) {
                log.warn("recall traceId={} select 异常: {}", traceId, e.getMessage());
                selected = tags;  // 降级用全集
                selectDegraded = true;
                steps.add(step("select", t2, selected.size(), false));
                notes.add("select 异常降级用全集: " + e.getMessage());
            }
            // 启发式：selector 内部降级（LLM 全失败用 candidates 全集，selector 不暴露信号）
            if (tags.size() > MemoryTagSelector.COARSE_TOP
                    && selected.size() == tags.size() && !selected.isEmpty()) {
                selectDegraded = true;
                notes.add("select 启发式降级：selected==候选全集(size=" + tags.size() + ">" + MemoryTagSelector.COARSE_TOP + ")");
            }

            // ④⑤ read（向量 14 恒只读自己，最多 1 次 LLM）
            selectedTagIds = selected.stream()
                    .map(RecallTagMeta::getId).filter(Objects::nonNull).toList();
            long t3 = System.nanoTime();
            try {
                summaries = reader.read(query, selectedTagIds, scope, userId, model);
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

        // ⑥.5 文件记忆召回 + 深读（记忆二期 P3 · FR-203 → 5x 四轮 U3 向量门控）：
        // 个人域 READY 文件记忆按 ③ 选中标签取候选 → recallGated 向量门控（≥1 分块距离 ≤ 阈值才出卡）
        // + 深读分块（per-file ≤2、总 ≤5）。文件记忆为个人域资产：仅 personalOn 时召回。
        // 独立 try/catch 降级跳过，绝不动主干。
        long t4h = System.nanoTime();
        List<RecalledFileCard> fileCards = List.of();
        List<MemoryAssetRecallService.DeepReadChunk> fileChunks = List.of();
        float[] fileQueryVec = null;   // 一次 embed 供个人卡门 + ⑦ 项目卡门复用
        boolean personalFileOn = scope.personalOn() && !selectedTagIds.isEmpty();
        if (personalFileOn && selectDegraded) {
            // 降级轮（selected=全集）：标签重叠无相关性意义 → 跳过文件召回（宁缺勿噪，5x 四轮 U3）
            notes.add("select 降级轮：跳过文件召回（宁缺勿噪）");
            steps.add(new RecallTraceStep("file-recall", 0, 0, true));
            steps.add(new RecallTraceStep("file-deepread", 0, 0, true));
        } else if (personalFileOn) {
            try {
                List<RecalledFileCard> candidates = assetRecallService.collectFileCards(selectedTagIds, userId);
                steps.add(step("file-recall", t4h, candidates.size(), true));
                if (!candidates.isEmpty()) {
                    float[] queryVec = assetRecallService.embedQuery(query, userId);
                    long t4i = System.nanoTime();
                    if (queryVec == null) {
                        notes.add("query embed 失败：零文件卡（宁缺勿噪）");
                        steps.add(new RecallTraceStep("file-deepread", 0, 0, true));
                    } else {
                        fileQueryVec = queryVec;
                        MemoryAssetRecallService.GatedFileRecall gated =
                                assetRecallService.recallGated(candidates, queryVec);
                        fileCards = gated.cards();
                        fileChunks = gated.chunks();
                        steps.add(step("file-deepread", t4i, fileChunks.size(), true));
                    }
                } else {
                    steps.add(new RecallTraceStep("file-deepread", 0, 0, true));
                }
            } catch (Exception e) {
                log.warn("recall traceId={} file-recall 失败: {}", traceId, e.getMessage());
                fileCards = List.of();
                fileChunks = List.of();
                steps.add(step("file-recall", t4h, 0, false));
                steps.add(new RecallTraceStep("file-deepread", 0, 0, true));
                notes.add("file-recall 失败: " + e.getMessage());
            }
        } else {
            // 个人域关闭 / 无选中标签：不查文件记忆，打点 count=0（非异常）
            steps.add(new RecallTraceStep("file-recall", 0, 0, true));
            steps.add(new RecallTraceStep("file-deepread", 0, 0, true));
        }

        // ⑦ assemble（按 subject 聚合打 owner 前缀 + 项目条目打作者前缀 + 文件卡片/深读块）
        long t5 = System.nanoTime();
        List<MemoryProjectEntryVO> entriesToAssemble = selectEntriesForAssemble(entries, selected, tags);
        // 项目收录的附件（FILE 条目）→ 下载卡片（记忆二期 P3 扩展：教学课件在项目上下文召回须可下载，
        // 下载鉴权走 MemoryFileEntryAccessGrantor「成员可读」咽喉）。与个人文件记忆卡片按 fileId 去重
        // （个人卡优先——有展开分块；同一文件若已是本人记忆则不重复出项目卡）。
        List<RecalledFileCard> projectFileCards = List.of();
        boolean hasFileEntry = entriesToAssemble.stream().anyMatch(e ->
                MemoryProjectEntry.CONTENT_TYPE_FILE.equals(e.getContentType())
                        && e.getFileId() != null && !e.getFileId().isBlank());
        if (hasFileEntry) {
            try {
                List<RecalledFileCard> cards = assetRecallService.collectFileCardsForEntries(entriesToAssemble);
                projectFileCards = cards == null ? List.of() : cards;
                // 5x 四轮 U3：项目附件下载卡同过向量门（原「项目 FILE 条目恒拼恒展示」是无关文件
                // 刷屏放大器——无标签条目绕过 ③ 选择直达展示）。embed 缺则现算（⑥.5 未算过时）。
                if (!projectFileCards.isEmpty()) {
                    if (fileQueryVec == null) {
                        fileQueryVec = assetRecallService.embedQuery(query, userId);
                    }
                    if (fileQueryVec == null) {
                        notes.add("query embed 失败：项目文件卡不展示（宁缺勿噪）");
                        projectFileCards = List.of();
                    } else {
                        projectFileCards = assetRecallService.gateProjectCards(projectFileCards, fileQueryVec);
                    }
                }
            } catch (Exception e) {
                log.warn("recall traceId={} 项目文件卡片构建失败: {}", traceId, e.getMessage());
                projectFileCards = List.of();
                notes.add("项目文件卡片构建失败: " + e.getMessage());
            }
        }
        List<RecalledFileCard> mergedFileCards = fileCards;
        if (!projectFileCards.isEmpty()) {
            Set<String> personalFileIds = fileCards.stream()
                    .map(RecalledFileCard::getFileId).filter(Objects::nonNull).collect(Collectors.toSet());
            List<RecalledFileCard> deduped = projectFileCards.stream()
                    .filter(c -> !personalFileIds.contains(c.getFileId())).toList();
            if (!deduped.isEmpty()) {
                mergedFileCards = new ArrayList<>(fileCards);
                mergedFileCards.addAll(deduped);
            }
        }
        String assembledText = assemble(summaries, turns, selected, userId, entriesToAssemble, mergedFileCards, fileChunks);
        steps.add(step("assemble", t5,
                summaries.size() + turns.size() + entriesToAssemble.size() + mergedFileCards.size() + fileChunks.size(), true));

        // 二期 P1：turns 纯个人域（召回 turns 恒本人），I3「已离开人员」标注随项目 turns 召回消亡下线
        return finish(assembledText, selected, summaries.size(), turns.size(), mergedFileCards, traceId, steps, notes, tStart);
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
     * <p>
     * <b>无标签条目恒拼</b>：单条 tag_ids 为空的条目（项目收录课件/附件，确定性上下文，无标签可筛）
     * 一律保留——与「tags 全空→全拼」同义。否则 scope 内一旦存在任何标签源（个人记忆 / 其他项目 summary），
     * aggregate 非空即走 selection，空标签的收录课件会被吞掉（教学课件永远召不回）。
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
                .filter(e -> {
                    List<Long> et = e.getTagIds();
                    // 无标签条目（收录课件/附件）恒拼：无标签可筛 = 确定性上下文。
                    if (et == null || et.isEmpty()) {
                        return true;
                    }
                    return et.stream().anyMatch(selectedIds::contains);
                })
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
        return assemble(summaries, turns, selectedTags, userId, entries, null, null);
    }

    /**
     * 全量装配（二期 P3 ⑥.5 扩展）：在总结/项目条目/对话流水之外追加——
     * <ul>
     *   <li><b>【文件记忆】</b>：{@code - 《名》（类型·共N块·可下载|原文件已删除·file:fileId）：l1}，
     *       l2 非空换行续接（文件卡片块，CLEANED 文件总结仍可召回但标失效不可下载）。</li>
     *   <li><b>【文件深读】</b>：{@code - 《名》[pageRef]：chunkText}，块头明示「回答引用须带页码锚点」
     *       （D-19.12 幻觉对冲）。</li>
     * </ul>
     */
    String assemble(List<RecalledSummary> summaries, List<MemoryTurn> turns,
                    List<RecallTagMeta> selectedTags, Long userId, List<MemoryProjectEntryVO> entries,
                    List<RecalledFileCard> fileCards, List<MemoryAssetRecallService.DeepReadChunk> fileChunks) {
        if ((summaries == null || summaries.isEmpty()) && (turns == null || turns.isEmpty())
                && (entries == null || entries.isEmpty())
                && (fileCards == null || fileCards.isEmpty()) && (fileChunks == null || fileChunks.isEmpty())) {
            return "";
        }
        Map<Long, RecallTagMeta> tagMap = (selectedTags == null ? List.<RecallTagMeta>of() : selectedTags).stream()
                .filter(t -> t != null && t.getId() != null)
                .collect(Collectors.toMap(RecallTagMeta::getId, t -> t, (a, b) -> a));
        // fileId → 文件卡片（项目 FILE 条目行尾标注附件可下载/已删除用，记忆二期 P3 扩展）
        Map<String, RecalledFileCard> cardByFile = (fileCards == null ? List.<RecalledFileCard>of() : fileCards).stream()
                .filter(c -> c.getFileId() != null && !c.getFileId().isBlank())
                .collect(Collectors.toMap(RecalledFileCard::getFileId, c -> c, (a, b) -> a));

        StringBuilder sb = new StringBuilder();
        if (summaries != null && !summaries.isEmpty()) {
            sb.append("【记忆总结】\n");
            for (RecalledSummary rs : summaries) {
                MemorySummary s = rs.summary();
                RecallTagMeta tag = s.getTagId() == null ? null : tagMap.get(s.getTagId());
                String subject = tag == null ? null : tag.getSubject();
                String topic = tag == null || tag.getTopic() == null ? "" : tag.getTopic();
                // 二期 P4（FR-305 装配来源标注）：项目共享总结（项目资产，user_id NULL）
                // 标「项目共享·」前缀，与本人/成员个人总结区分
                String sharedPrefix = "PROJECT".equals(s.getScopeOwner()) ? "项目共享·" : "";
                sb.append("- ").append(sharedPrefix)
                        .append(ownerSubjectPrefix(s.getUserId(), subject, userId))
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
                // 二期 P2（FR-102）：经 ACTIVE 授权链合流的 child 条目带来源标注「来自授权项目·X」
                String sourcePrefix = Boolean.TRUE.equals(e.getViaAuthorizedLink())
                        ? "来自授权项目·" + (e.getProjectName() != null && !e.getProjectName().isBlank()
                                ? e.getProjectName() : "项目#" + e.getProjectId()) + "·"
                        : "";
                sb.append("- ").append(sourcePrefix).append(author).append('·')
                        .append(tagLabel.isEmpty() ? "收录" : tagLabel)
                        .append("：").append(e.getL1Summary() == null ? "" : e.getL1Summary());
                // 项目收录附件（FILE 条目）行尾标注下载回链（LLM 据此告知「可下载」，前端卡片独立渲染下载按钮）
                if (MemoryProjectEntry.CONTENT_TYPE_FILE.equals(e.getContentType())
                        && e.getFileId() != null && !e.getFileId().isBlank()) {
                    RecalledFileCard fc = cardByFile.get(e.getFileId());
                    String fname = fc != null && fc.getOriginalName() != null && !fc.getOriginalName().isBlank()
                            ? fc.getOriginalName() : "附件";
                    sb.append("（附件《").append(fname).append("》");
                    if (fc != null && fc.isFileCleaned()) {
                        sb.append("原文件已删除");
                    } else {
                        sb.append("可下载·file:").append(e.getFileId());
                    }
                    sb.append("）");
                }
                sb.append('\n');
            }
        }
        // 【文件记忆】仅装配个人文件卡片（memoryId != null）；项目来源卡片（memoryId=null）
        // 已在【项目记忆】段标注下载，不重复入此块——故先过滤，避免空块残留「【文件记忆】」表头。
        List<RecalledFileCard> personalFileCards = (fileCards == null ? List.<RecalledFileCard>of() : fileCards).stream()
                .filter(c -> c.getMemoryId() != null).toList();
        if (!personalFileCards.isEmpty()) {
            sb.append("【文件记忆】\n");
            for (RecalledFileCard c : personalFileCards) {
                String name = c.getOriginalName() == null || c.getOriginalName().isBlank()
                        ? "未命名文件" : c.getOriginalName();
                sb.append("- 《").append(name).append("》（")
                        .append(com.superprogrammer.chat.entity.MemoryAssetMemory.kindLabel(c.getFileKind()))
                        .append("·共").append(c.getChunkCount()).append("块·")
                        .append(c.isFileCleaned() ? "原文件已删除" : "可下载")
                        .append("·file:").append(c.getFileId() == null ? "-" : c.getFileId())
                        .append("）：").append(c.getL1() == null ? "" : c.getL1());
                if (c.getL2() != null && !c.getL2().isBlank()) {
                    sb.append('\n').append(c.getL2());
                }
                sb.append('\n');
            }
        }
        if (fileChunks != null && !fileChunks.isEmpty()) {
            sb.append("【文件深读】（以下为文件分块原文，回答引用须带页码锚点）\n");
            for (MemoryAssetRecallService.DeepReadChunk ch : fileChunks) {
                sb.append("- 《").append(ch.fileName()).append("》")
                        .append('[').append(ch.pageRef() == null ? "?" : ch.pageRef()).append("]：")
                        .append(ch.chunkText() == null ? "" : ch.chunkText()).append('\n');
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
                                      int summaryCount, int turnCount, List<RecalledFileCard> fileCards,
                                      String traceId, List<RecallTraceStep> steps, List<String> notes, long tStart) {
        boolean degraded = !notes.isEmpty();
        long totalMs = (System.nanoTime() - tStart) / 1_000_000L;
        log.info("recall 完成 traceId={} summaryCount={} turnCount={} fileCards={} degraded={} notes={} 耗时 {}ms",
                traceId, summaryCount, turnCount, fileCards == null ? 0 : fileCards.size(),
                degraded, notes.size(), totalMs);
        return MemoryRecallResult.builder()
                .assembledText(assembledText)
                .selectedTags(selectedTags)
                .summaryCount(summaryCount)
                .turnCount(turnCount)
                .fileCards(fileCards)
                .degraded(degraded)
                .notes(notes)
                .traceId(traceId)
                .steps(steps)
                .build();
    }
}
