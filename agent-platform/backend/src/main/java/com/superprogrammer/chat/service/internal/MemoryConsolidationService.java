package com.superprogrammer.chat.service.internal;

import com.superprogrammer.chat.dto.MemoryConsolidationScopeRequest;
import com.superprogrammer.chat.dto.MemoryConsolidationTargetView;
import com.superprogrammer.chat.dto.MemoryConsolidationTriggerRequest;
import com.superprogrammer.chat.dto.MemoryGenMatrixItemVO;
import com.superprogrammer.chat.dto.MemoryProjectEntryVO;
import com.superprogrammer.chat.dto.RecallTagMeta;
import com.superprogrammer.chat.entity.MemoryConsolidationScope;
import com.superprogrammer.chat.entity.MemoryProjectEntry;
import com.superprogrammer.chat.entity.MemorySummaryCoverage;
import com.superprogrammer.chat.entity.MemoryTag;
import com.superprogrammer.chat.entity.MemoryTurn;
import com.superprogrammer.chat.mapper.MemoryConsolidationScopeMapper;
import com.superprogrammer.chat.mapper.MemoryEntryCoverageMapper;
import com.superprogrammer.chat.mapper.MemoryProjectEntryMapper;
import com.superprogrammer.chat.mapper.MemoryProjectMemberMapper;
import com.superprogrammer.chat.mapper.MemorySummaryCoverageMapper;
import com.superprogrammer.chat.mapper.MemorySummaryMapper;
import com.superprogrammer.chat.mapper.MemoryTagMapper;
import com.superprogrammer.chat.mapper.MemoryTurnMapper;
import com.superprogrammer.chat.service.internal.MemoryConsolidationCompressor.CompressedEntrySummary;
import com.superprogrammer.chat.service.internal.MemoryConsolidationCompressor.CompressedSummary;
import com.superprogrammer.chat.service.internal.MemoryQueryCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 计划12 · E-3 · 总结编排服务（总体设计 §3.4 周期总结）。
 * <p>
 * 单 scope 总结流程（手动 / 自动同路径，{@code manual} 标记决定是否先 backfill raw）：
 * <pre>
 *   ① 解析 scope（personal / project，project 校验 accessible）
 *   ② [manual] backfill 该 scope gen_done=false raw（≤20/批）
 *   ③ 枚举 scope 内标签（复用 D-2 findPersonal/ProjectRecallTags 聚合）
 *   ④ per tag：取 gen_done=true turns → 查 coverage 判未覆盖 → 无未覆盖空跳过（幂等不调 LLM）
 *   ⑤ 压缩未覆盖 turns（Compressor + 日期铁律）→ 写 summary + batch coverage
 *   ⑥ 同 (user,tag,scope) 已有 CLEAN → judge 时序互斥（E-5）→ 互斥 PENDING + 冲突行 / 并存共 CLEAN
 *   ⑦ 防膨胀：同 (user,tag,scope) CLEAN 条数 > 阈值 → 再压一次（链缩短，source_summary_id 溯源）
 * </pre>
 * <p>
 * <b>偏离 plan</b>：plan 列「改 MemoryService」——legacy MemoryService 是 1278 行 user_memories 栈。
 * 新总结基 memory_summaries 新表，混入 = 新旧纠缠。故新建本类独立编排（承 C/D 隔离裁决）。
 * <p>
 * <b>事务粒度</b>：per-tag 写入走 {@code @Transactional}（summary+coverage+冲突原子）；LLM 压缩在事务外
 * （Compressor 无 @Transactional），仅写库段事务化（同 IndexJobWorker 范式：LLM 不持事务）。
 *
 * @see MemoryConsolidationCompressor 压缩 + 日期铁律
 * @see MemoryBackfillService raw 补 tag（manual）
 * @see MemoryConflictJudge judgeSummaryConflict 时序互斥判定
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryConsolidationService {

    private final MemoryTurnMapper turnMapper;
    private final MemorySummaryMapper summaryMapper;
    private final MemorySummaryCoverageMapper coverageMapper;
    private final MemoryTagMapper tagMapper;
    private final MemoryConsolidationScopeMapper scopeMapper;
    private final MemoryBackfillService backfillService;
    private final MemoryConsolidationCompressor compressor;
    private final MemoryConsolidationTxService txService;
    private final MemoryQueryCache queryCache;
    private final MemoryProjectEntryMapper entryMapper;
    private final MemoryEntryCoverageMapper entryCoverageMapper;
    private final MemoryProjectLinkService linkService;
    private final MemoryProjectMemberMapper memberMapper;

    /** 防膨胀阈值（同 user+tag+scope CLEAN 条数 > 此值 → 再压一次）。走 system_settings 可配，v1 走默认。 */
    @Value("${memory.consolidation.bloat-threshold:5}")
    private int bloatThreshold;

    /**
     * 总结单 scope。
     *
     * @param userId 当前用户（作者，summary.user_id 恒 = 此）
     * @param req    scope 取数配置
     * @param manual true=手动入口（先 backfill raw，独立于开关）；false=定时（不 backfill，gen 关态空跳过）
     * @return 结果（写 summary 条数 + 建冲突条数 + 降级 notes）
     */
    public SummarizeResult summarizeScope(Long userId, MemoryConsolidationScopeRequest req, boolean manual) {
        SummarizeResult result = new SummarizeResult();
        if (req == null) {
            result.addNote("scope 请求空");
            return result;
        }
        boolean personal = isPersonalScope(req);
        // 二期人工测试 Req2：force=重新总结（跳过未覆盖幂等闸，强制重压）。
        boolean force = Boolean.TRUE.equals(req.getForce());
        // 二期 P4（V70，FR-301/302）：项目总结重建——基于收录条目（memory_project_entries），
        // 共享（scope_owner=PROJECT，owner/admin）/ 成员个人压缩（toPersonal=true）双通道。
        if (!personal) {
            return summarizeProjectScope(userId, req, force, result);
        }
        Long scopeProjectId = null;
        String direction = normalizeDirection(req.getDirection());

        // ② manual 先 backfill raw（定时路径不 backfill，gen 关态空跳过）
        boolean changed = false;
        if (manual) {
            int backfilled = backfillService.backfillScope(userId);
            if (backfilled > 0) {
                result.addNote("backfill " + backfilled + " 条 raw");
                changed = true;
            }
        }

        // ③ 枚举 scope 内标签（P3b：带时间窗 twStart/twEnd/relativeDays）
        List<RecallTagMeta> tags = tagMapper.findPersonalRecallTags(userId, direction,
                req.getStart(), req.getEnd(), req.getRelativeDays());
        if (tags == null || tags.isEmpty()) {
            return result;  // scope 无标签 → 空跑
        }
        // P3b：指定标签集 → 仅总结交集（标签 id 过滤）
        List<RecallTagMeta> scopedTags = filterTagsByIds(tags, req.getTagIds());
        if (scopedTags.isEmpty()) {
            return result;
        }

        // ④~⑦ per-tag 压缩
        for (RecallTagMeta tag : scopedTags) {
            try {
                summarizeOneTag(userId, scopeProjectId, direction, tag,
                        req.getStart(), req.getEnd(), req.getRelativeDays(), force, result);
            } catch (Exception e) {
                log.warn("总结单 tag 异常 userId={} tagId={}: {}", userId, tag.getId(), e.getMessage(), e);
                result.addNote("tag " + tag.getId() + " 异常: " + e.getMessage());
            }
        }

        if (changed || result.summariesWritten > 0) {
            queryCache.evictUser(userId);
        }
        log.info("总结 scope 完成 userId={} summaries={} conflicts={} notes={}",
                userId, result.summariesWritten, result.conflictsCreated, result.notes);
        return result;
    }

    /** per-tag：取数 → 未覆盖判定 → 压缩 → 写 summary+coverage → 冲突检测 → 防膨胀。
     *  P3b：start/end/relativeDays 时间窗透传给取数（mapper 内 relativeDays 优先）。
     *  二期人工测试 Req2：force=true 跳过未覆盖闸（重新总结，强制重压）。 */
    private void summarizeOneTag(Long userId, Long scopeProjectId,
                                 String direction, RecallTagMeta tag,
                                 OffsetDateTime start, OffsetDateTime end, Integer relativeDays,
                                 boolean force, SummarizeResult result) {
        Long tagId = tag.getId();
        List<MemoryTurn> turns =
                turnMapper.findPersonalTurnsForConsolidation(userId, List.of(tagId), direction, start, end, relativeDays);
        if (turns == null || turns.isEmpty()) {
            return;  // 该 tag 无 gen_done=true turn
        }

        // ④ 未覆盖判定：查 coverage，剔已覆盖 turn（幂等——无未覆盖不调 LLM）
        List<MemoryTurn> uncovered = force ? turns : filterUncovered(userId, tagId, scopeProjectId, turns);
        if (uncovered.isEmpty()) {
            return;  // 全已覆盖 → 空跳过（设计 §3.4 line123 无新增不耗 token）
        }

        // ⑤ 压缩（事务外，Compressor 内 3 重试 + 日期铁律断言）
        CompressedSummary cs = compressor.compress(userId, tag.getLabel(), uncovered);
        if (cs == null) {
            result.addNote("tag " + tagId + " 压缩失败/日期铁律违则 skip");
            return;
        }

        // ⑥ 写 summary + coverage + 冲突检测（TxService 事务化，跨 bean 代理生效）
        txService.writeSummaryAndCoverage(userId, scopeProjectId, tagId, tag.getLabel(),
                direction, uncovered, cs, result);

        // ⑦ 防膨胀
        if (bloatThreshold > 0 && summaryMapper.countByUserTagScope(userId, tagId, scopeProjectId) > bloatThreshold) {
            result.addNote("tag " + tagId + " 触发防膨胀（>" + bloatThreshold + "）");
            log.info("防膨胀触发 userId={} tagId={} scope={} → 待再压缩", userId, tagId, scopeProjectId);
            // 再压缩实现留 E-6 worker 调度（同链 source_summary_id 压缩），此处仅检测打点
        }
    }

    /** 未覆盖判定：turn 在 (user,tag,scope) 无 coverage 行 → 未覆盖。 */
    private List<MemoryTurn> filterUncovered(Long userId, Long tagId, Long scopeProjectId, List<MemoryTurn> turns) {
        List<Long> turnIds = turns.stream().map(MemoryTurn::getId).toList();
        List<MemorySummaryCoverage> covered = coverageMapper.findByUserAndTurns(userId, turnIds);
        Set<Long> coveredTurnIds = new HashSet<>();
        for (MemorySummaryCoverage c : covered) {
            // 同 tag + 同 scope(project_id 匹配,个人 null) 才算该 scope 下已覆盖
            if (tagId.equals(c.getTagId()) && projectIdEquals(scopeProjectId, c.getProjectId())) {
                coveredTurnIds.add(c.getTurnId());
            }
        }
        List<MemoryTurn> uncovered = new ArrayList<>();
        for (MemoryTurn t : turns) {
            if (!coveredTurnIds.contains(t.getId())) {
                uncovered.add(t);
            }
        }
        return uncovered;
    }

    // ============================ 二期 P4 · 项目总结（V70，FR-301/302/303/305）============================

    /**
     * 项目 scope 总结（二期 P4 重建，取数=收录条目非 turns）。
     * <pre>
     *   ① 权限：共享（toPersonal!=true）须 owner/admin（FR-301）；成员个人压缩须 ACTIVE 成员（FR-302）
     *   ② 取数：本项目 ACTIVE 条目 ∪ ACTIVE links child 项目条目（FR-303 嵌套，实时算链，单级一跳）
     *   ③ 按 tag 分组（tag_ids 数组展开；标签归一在作者个人库，仅借 label 喂 prompt）
     *   ④ per tag：entry_coverage 判未覆盖（共享 user_id=NULL / 个人 user_id=self，各自幂等）
     *   ⑤ 压缩（compressEntries + 日期铁律）→ 事务写 summary(scope_owner) + entry_coverage + 冲突
     * </pre>
     * 撤销授权后重压天然不含 child 内容（取数实时算 ACTIVE 链，坑点预判③）。
     */
    private SummarizeResult summarizeProjectScope(Long operatorId, MemoryConsolidationScopeRequest req,
                                                  boolean force, SummarizeResult result) {
        Long projectId = req.getProjectId();
        if (projectId == null) {
            result.addNote("项目 scope 缺 projectId");
            return result;
        }
        boolean shared = !Boolean.TRUE.equals(req.getToPersonal());
        // ① 权限咽喉——二期人工测试 Req1：非项目创始人（OWNER）不可总结该项目（共享/个人均禁），
        //    仅可查看与召回。创始人=projects 建人，role=OWNER。
        if (!linkService.isOwner(projectId, operatorId)) {
            result.addNote("项目 " + projectId + " 总结仅创始人(OWNER)可写 → 跳过");
            log.info("项目总结越权拦截(非创始人) operatorId={} projectId={} shared={}", operatorId, projectId, shared);
            return result;
        }

        // ② 取数：本项目 ∪ ACTIVE child（实时算链）
        List<Long> sourceProjectIds = new ArrayList<>();
        sourceProjectIds.add(projectId);
        sourceProjectIds.addAll(linkService.findActiveChildIds(List.of(projectId)));
        List<MemoryProjectEntryVO> entries = entryMapper.listActiveForRecall(sourceProjectIds);
        if (entries == null || entries.isEmpty()) {
            return result;  // 无 ACTIVE 条目 → 空跑
        }
        // P3b：时间窗过滤 entry.created_at（relativeDays 非空 → 折算 [now-N, now]）
        List<MemoryProjectEntryVO> timeScoped = filterEntriesByTime(entries, req);
        if (timeScoped.isEmpty()) {
            return result;
        }
        // 5x #4：方向过滤——direction≠BOTH 时仅取该方向条目（BOTH 兜底集合恒入选），修复前项目总结恒吃全量
        String direction = normalizeDirection(req.getDirection());
        List<MemoryProjectEntryVO> directionScoped = "BOTH".equals(direction) ? timeScoped
                : timeScoped.stream().filter(e -> direction.equals(e.getDirection())
                        || MemoryProjectEntry.DIRECTION_BOTH.equals(e.getDirection())
                        || e.getDirection() == null).toList();
        if (directionScoped.isEmpty()) {
            return result;
        }

        // ③ 按 tag 分组（tag_ids 展开；无 tag 条目不进总结——无分组锚点）
        Map<Long, List<MemoryProjectEntryVO>> byTag = new LinkedHashMap<>();
        Set<Long> tagIds = new HashSet<>();
        for (MemoryProjectEntryVO e : directionScoped) {
            if (e.getTagIds() == null) continue;
            for (Long tid : e.getTagIds()) {
                byTag.computeIfAbsent(tid, k -> new ArrayList<>()).add(e);
                tagIds.add(tid);
            }
        }
        // P3b：指定标签集 → byTag 仅留交集
        if (req.getTagIds() != null && !req.getTagIds().isEmpty()) {
            byTag.keySet().retainAll(new HashSet<>(req.getTagIds()));
        }
        if (byTag.isEmpty()) {
            return result;
        }
        Map<Long, String> tagLabels = new HashMap<>();
        for (MemoryTag t : tagMapper.selectBatchIds(tagIds)) {
            tagLabels.put(t.getId(), t.getLabel());
        }

        // ④⑤ per tag：未覆盖判定 → 压缩 → 事务写
        for (Map.Entry<Long, List<MemoryProjectEntryVO>> group : byTag.entrySet()) {
            Long tagId = group.getKey();
            List<MemoryProjectEntryVO> groupEntries = group.getValue();
            groupEntries.sort(Comparator.comparing(MemoryProjectEntryVO::getCreatedAt,
                    Comparator.nullsLast(Comparator.naturalOrder())));
            try {
                summarizeOneEntryTag(operatorId, projectId, shared, tagId,
                        tagLabels.getOrDefault(tagId, "总结"), groupEntries, force, direction, result);
            } catch (Exception e) {
                log.warn("项目总结单 tag 异常 operatorId={} projectId={} tagId={}: {}",
                        operatorId, projectId, tagId, e.getMessage(), e);
                result.addNote("tag " + tagId + " 异常: " + e.getMessage());
            }
        }
        if (result.summariesWritten > 0) {
            queryCache.evictUser(operatorId);
        }
        log.info("项目总结完成 operatorId={} projectId={} shared={} summaries={} conflicts={}",
                operatorId, projectId, shared, result.summariesWritten, result.conflictsCreated);
        return result;
    }

    /** per tag：entry_coverage 未覆盖判定（幂等不调 LLM）→ 压缩 → 事务写。direction 随 req（5x #4）。 */
    private void summarizeOneEntryTag(Long operatorId, Long projectId, boolean shared, Long tagId,
                                      String tagLabel, List<MemoryProjectEntryVO> groupEntries,
                                      boolean force, String direction, SummarizeResult result) {
        List<Long> entryIds = groupEntries.stream().map(MemoryProjectEntryVO::getId).toList();
        Set<Long> covered = force ? Set.of() : new HashSet<>(entryCoverageMapper.findCoveredEntryIds(
                entryIds, projectId, tagId, shared ? null : operatorId, direction));
        List<MemoryProjectEntryVO> uncovered = new ArrayList<>();
        for (MemoryProjectEntryVO e : groupEntries) {
            if (force || !covered.contains(e.getId())) {
                uncovered.add(e);
            }
        }
        if (uncovered.isEmpty()) {
            return;  // 全已覆盖 → 空跳过（无新增不耗 token）
        }
        CompressedEntrySummary cs = compressor.compressEntries(operatorId, tagLabel, uncovered);
        if (cs == null) {
            result.addNote("tag " + tagId + " 条目压缩失败/日期铁律违则 skip");
            return;
        }
        // 二期 P3c：项目条目（蒸馏产物）无方向 → 总结记 BOTH。
        // 5x #4：总结方向跟随请求（V80 已有 direction 列，二期 P3c 曾硬编码 BOTH）
        txService.writeProjectSummaryAndCoverage(operatorId, projectId, shared, tagId, direction, uncovered, cs, result);
    }

    // ---- scope 解析 helpers ----

    private static boolean isPersonalScope(MemoryConsolidationScopeRequest req) {
        if (req.getProjectId() == null) return true;
        String kind = req.getScopeKind();
        return kind == null || "PERSONAL".equalsIgnoreCase(kind);
    }

    private static String normalizeDirection(String direction) {
        if (direction == null || direction.isBlank()) return "BOTH";
        String d = direction.trim().toUpperCase();
        return switch (d) {
            case "INPUT", "OUTPUT", "BOTH" -> d;
            default -> "BOTH";
        };
    }

    /** P3b：标签集非空时仅留交集（PERSONAL 枚举标签后过滤）。 */
    private static List<RecallTagMeta> filterTagsByIds(List<RecallTagMeta> tags, List<Long> wantedIds) {
        if (wantedIds == null || wantedIds.isEmpty()) {
            return tags;
        }
        Set<Long> wanted = new HashSet<>(wantedIds);
        return tags.stream().filter(t -> wanted.contains(t.getId())).toList();
    }

    /** P3b：按创建时间过滤项目条目。relativeDays 非空 → [now-N, now]；否则用 start/end（null=不限）。 */
    private static List<MemoryProjectEntryVO> filterEntriesByTime(List<MemoryProjectEntryVO> entries,
                                                                  MemoryConsolidationScopeRequest req) {
        OffsetDateTime start = null;
        OffsetDateTime end = null;
        if (req.getRelativeDays() != null && req.getRelativeDays() > 0) {
            start = OffsetDateTime.now().minusDays(req.getRelativeDays());
            end = null;  // 上界 = 至今
        } else {
            start = req.getStart();
            end = req.getEnd();
        }
        if (start == null && end == null) {
            return entries;  // 无时间窗 → 不过滤
        }
        List<MemoryProjectEntryVO> out = new ArrayList<>();
        for (MemoryProjectEntryVO e : entries) {
            OffsetDateTime c = e.getCreatedAt();
            if (c == null) {
                continue;  // 无创建时间不纳入时间窗总结
            }
            if (start != null && c.isBefore(start)) continue;
            if (end != null && c.isAfter(end)) continue;
            out.add(e);
        }
        return out;
    }

    private static boolean projectIdEquals(Long a, Long b) {
        return a == null ? b == null : a.equals(b);
    }

    /** 总结结果。字段包可见供 {@link MemoryConsolidationTxService} 原子写后累加。 */
    public static class SummarizeResult {
        int summariesWritten;
        int conflictsCreated;
        final List<String> notes = new ArrayList<>();

        public int getSummariesWritten() { return summariesWritten; }
        public int getConflictsCreated() { return conflictsCreated; }
        public List<String> getNotes() { return notes; }

        void addNote(String n) { notes.add(n); }
    }

    // ============================ E-7 手动触发 + 入口枚举 ============================

    /**
     * 手动总结触发（设计 §3.4 统一入口）：多 scope 串行，每 scope 先 acquireManualLock（CAS 与定时 worker
     * 互斥）→ summarizeScope(manual=true，含 backfill raw）→ 释放锁。
     */
    public SummarizeResult triggerManual(Long userId, MemoryConsolidationTriggerRequest req) {
        SummarizeResult aggregate = new SummarizeResult();
        if (req == null || req.getScopes() == null || req.getScopes().isEmpty()) {
            aggregate.addNote("无 scope");
            return aggregate;
        }
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime lockUntil = now.plusMinutes(LOCK_MINUTES);
        for (MemoryConsolidationScopeRequest sr : req.getScopes()) {
            try {
                Long scopeId = ensureScopeRow(userId, sr);
                if (scopeId == null) {
                    aggregate.addNote("scope 行无法建立/项目越权 → 跳过");
                    continue;
                }
                if (scopeMapper.acquireManualLock(scopeId, now, lockUntil) == 0) {
                    aggregate.addNote("scope " + scopeId + " 正在跑（锁占用）→ 跳过");
                    continue;
                }
                try {
                    SummarizeResult r = summarizeScope(userId, sr, true);
                    aggregate.summariesWritten += r.summariesWritten;
                    aggregate.conflictsCreated += r.conflictsCreated;
                    aggregate.notes.addAll(r.notes);
                    scopeMapper.releaseLockSuccess(scopeId, OffsetDateTime.now());
                } catch (Exception e) {
                    scopeMapper.releaseLockFailure(scopeId);
                    aggregate.addNote("scope " + scopeId + " 失败: " + e.getMessage());
                    log.warn("手动总结 scope 失败 userId={} scopeId={}: {}", userId, scopeId, e.getMessage(), e);
                }
            } catch (Exception e) {
                aggregate.addNote("scope 异常: " + e.getMessage());
            }
        }
        return aggregate;
    }

    /** 取/建 scope 行（PERSONAL 由 trigger 默认建；二期 P4 PROJECT=成员即可建行，写权在 summarizeProjectScope 咽喉判）。 */
    private Long ensureScopeRow(Long userId, MemoryConsolidationScopeRequest sr) {
        boolean personal = isPersonalScope(sr);
        if (!personal && sr.getProjectId() == null) {
            return null;
        }
        if (!personal && !linkService.isActiveMember(sr.getProjectId(), userId)) {
            log.info("总结 scope 建行越权拦截 userId={} projectId={}", userId, sr.getProjectId());
            return null;  // 非 ACTIVE 成员不可建项目 scope（P4：成员也可触发个人压缩，但须是成员）
        }
        String kind = personal ? "PERSONAL" : "PROJECT";
        Long projectId = personal ? null : sr.getProjectId();
        OffsetDateTime now = OffsetDateTime.now();
        scopeMapper.upsertScope(userId, kind, projectId, false, now);  // 手动触发不自动改 auto_enabled
        MemoryConsolidationScope row = scopeMapper.findByUserAndScope(userId, kind, projectId);
        return row == null ? null : row.getId();
    }

    /**
     * 列总结入口（设计 §3.4 line119）：{个人} ∪ {本人 ACTIVE 项目}（二期 P4 重建项目总结）。
     * 个人 uncoveredCount = gen_done=true 且无 coverage 的 turn（§3.9 告警阈值用）；
     * 项目 uncoveredCount = 条目级未覆盖计数（共享通道 user_id=NULL；成员另见 canWriteShared）。
     */
    public List<MemoryConsolidationTargetView> listTargets(Long userId) {
        List<MemoryConsolidationTargetView> out = new ArrayList<>();
        int uncovered = turnMapper.countUncoveredPersonalTurns(userId);
        int raw = turnMapper.countRawPersonalTurns(userId);
        MemoryConsolidationScope personal = scopeMapper.findByUserAndScope(userId, "PERSONAL", null);
        out.add(MemoryConsolidationTargetView.builder()
                .scopeKind("PERSONAL")
                .projectId(null)
                .displayName("个人")
                .hasChange(uncovered > 0 || raw > 0)
                .uncoveredCount(uncovered)
                .autoEnabled(personal != null && Boolean.TRUE.equals(personal.getAutoEnabled()))
                .canWriteShared(true)
                .canSummarize(true)
                .build());

        // 二期 P4（FR-301/302）：本人 ACTIVE 项目逐个出入口（owner/admin 可写共享总结）
        // 二期人工测试 Req1：仅创始人(OWNER)可总结；非创始人 canSummarize=false（前端隐，后端 trigger 拦）。
        List<MemoryGenMatrixItemVO> myProjects = memberMapper.findMyGenMatrix(userId);
        if (myProjects != null) {
            for (MemoryGenMatrixItemVO p : myProjects) {
                boolean isOwner = "OWNER".equals(p.getRole());
                boolean canShared = isOwner || "ADMIN".equals(p.getRole());
                List<Long> sourceProjectIds = new ArrayList<>();
                sourceProjectIds.add(p.getProjectId());
                sourceProjectIds.addAll(linkService.findActiveChildIds(List.of(p.getProjectId())));
                // 未覆盖计数：owner/admin 看共享通道（user_id=NULL）；成员看个人通道（user_id=self）
                int entryUncovered = entryMapper.countUncoveredEntries(
                        sourceProjectIds, p.getProjectId(), canShared ? null : userId);
                MemoryConsolidationScope scopeRow = scopeMapper.findByUserAndScope(userId, "PROJECT", p.getProjectId());
                out.add(MemoryConsolidationTargetView.builder()
                        .scopeKind("PROJECT")
                        .projectId(p.getProjectId())
                        .displayName(p.getProjectName())
                        .hasChange(entryUncovered > 0)
                        .uncoveredCount(entryUncovered)
                        .autoEnabled(scopeRow != null && Boolean.TRUE.equals(scopeRow.getAutoEnabled()))
                        .canWriteShared(canShared)
                        .canSummarize(isOwner)
                        .build());
            }
        }
        return out;
    }

    /** 锁时长（与 worker 一致）。 */
    private static final int LOCK_MINUTES = 5;
}
