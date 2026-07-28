package com.superprogrammer.chat.service.internal;

import com.superprogrammer.chat.dto.MemoryConsolidationScopeRequest;
import com.superprogrammer.chat.dto.MemoryConsolidationTargetView;
import com.superprogrammer.chat.dto.MemoryConsolidationTriggerRequest;
import com.superprogrammer.chat.dto.RecallTagMeta;
import com.superprogrammer.chat.entity.MemoryConsolidationScope;
import com.superprogrammer.chat.entity.MemorySummaryCoverage;
import com.superprogrammer.chat.entity.MemoryTurn;
import com.superprogrammer.chat.mapper.MemoryConsolidationScopeMapper;
import com.superprogrammer.chat.mapper.MemorySummaryCoverageMapper;
import com.superprogrammer.chat.mapper.MemorySummaryMapper;
import com.superprogrammer.chat.mapper.MemoryTagMapper;
import com.superprogrammer.chat.mapper.MemoryTurnMapper;
import com.superprogrammer.chat.service.internal.MemoryConsolidationCompressor.CompressedSummary;
import com.superprogrammer.chat.service.internal.MemoryQueryCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
    private final MemoryRecallAclResolver aclResolver;
    private final MemoryQueryCache queryCache;

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
        Long scopeProjectId = personal ? null : req.getProjectId();

        // ① project scope 须本人可访问（向量 2 防越权取数）
        List<Long> authorIds;
        if (personal) {
            authorIds = List.of(userId);
        } else {
            authorIds = resolveAuthorIds(userId, scopeProjectId, req);
            if (authorIds.isEmpty()) {
                result.addNote("项目 scope 无可读作者（ACL 空）→ 跳过");
                return result;
            }
        }
        String direction = normalizeDirection(req.getDirection());

        // ② manual 先 backfill raw（定时路径不 backfill，gen 关态空跳过）
        boolean changed = false;
        if (manual) {
            int backfilled = backfillService.backfillScope(userId, scopeProjectId, personal);
            if (backfilled > 0) {
                result.addNote("backfill " + backfilled + " 条 raw");
                changed = true;
            }
        }

        // ③ 枚举 scope 内标签
        List<RecallTagMeta> tags = personal
                ? tagMapper.findPersonalRecallTags(userId, direction, null, null, null)
                : tagMapper.findProjectRecallTags(scopeProjectId, userId, authorIds, direction, null, null, null);
        if (tags == null || tags.isEmpty()) {
            return result;  // scope 无标签 → 空跑
        }

        // ④~⑦ per-tag 压缩
        for (RecallTagMeta tag : tags) {
            try {
                summarizeOneTag(userId, scopeProjectId, personal, authorIds, direction, tag, result);
            } catch (Exception e) {
                log.warn("总结单 tag 异常 userId={} tagId={}: {}", userId, tag.getId(), e.getMessage());
                result.addNote("tag " + tag.getId() + " 异常: " + e.getMessage());
            }
        }

        if (changed || result.summariesWritten > 0) {
            queryCache.evictUser(userId);
        }
        log.info("总结 scope 完成 userId={} personal={} projectId={} summaries={} conflicts={} notes={}",
                userId, personal, scopeProjectId, result.summariesWritten, result.conflictsCreated, result.notes);
        return result;
    }

    /** per-tag：取数 → 未覆盖判定 → 压缩 → 写 summary+coverage → 冲突检测 → 防膨胀。 */
    private void summarizeOneTag(Long userId, Long scopeProjectId, boolean personal, List<Long> authorIds,
                                 String direction, RecallTagMeta tag, SummarizeResult result) {
        Long tagId = tag.getId();
        List<MemoryTurn> turns = personal
                ? turnMapper.findPersonalTurnsForConsolidation(userId, List.of(tagId), direction, null, null, null)
                : turnMapper.findProjectTurnsForConsolidation(scopeProjectId, authorIds, List.of(tagId), direction, null, null, null);
        if (turns == null || turns.isEmpty()) {
            return;  // 该 tag 无 gen_done=true turn
        }

        // ④ 未覆盖判定：查 coverage，剔已覆盖 turn（幂等——无未覆盖不调 LLM）
        List<MemoryTurn> uncovered = filterUncovered(userId, tagId, scopeProjectId, turns);
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
        txService.writeSummaryAndCoverage(userId, scopeProjectId, tagId, tag.getLabel(), uncovered, cs, result);

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

    // ---- scope 解析 helpers ----

    private static boolean isPersonalScope(MemoryConsolidationScopeRequest req) {
        if (req.getProjectId() == null) return true;
        String kind = req.getScopeKind();
        return kind == null || "PERSONAL".equalsIgnoreCase(kind);
    }

    /** 项目 scope 取数作者集：SELF（仅自己）/ SPECIFIC（∩ readableAuthors）/ ALL（readableAuthors 全集）。 */
    private List<Long> resolveAuthorIds(Long userId, Long projectId, MemoryConsolidationScopeRequest req) {
        Set<Long> readable = aclResolver.readableAuthors(projectId, userId);
        if (readable == null || readable.isEmpty()) {
            return List.of();  // 无读权限 → 空（上层 skip，防越权向量 14）
        }
        String filter = req.getAuthorFilter();
        if ("SPECIFIC".equalsIgnoreCase(filter) && req.getAuthorIds() != null) {
            // ∩ readableAuthors（防越权读他人）
            List<Long> intersection = new ArrayList<>();
            for (Long aid : req.getAuthorIds()) {
                if (readable.contains(aid)) intersection.add(aid);
            }
            return intersection;
        }
        if ("ALL".equalsIgnoreCase(filter)) {
            return new ArrayList<>(readable);
        }
        // SELF 默认
        return List.of(userId);
    }

    private static String normalizeDirection(String direction) {
        if (direction == null || direction.isBlank()) return "BOTH";
        String d = direction.trim().toUpperCase();
        return switch (d) {
            case "INPUT", "OUTPUT", "BOTH" -> d;
            default -> "BOTH";
        };
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
                    log.warn("手动总结 scope 失败 userId={} scopeId={}: {}", userId, scopeId, e.getMessage());
                }
            } catch (Exception e) {
                aggregate.addNote("scope 异常: " + e.getMessage());
            }
        }
        return aggregate;
    }

    /** 取/建 scope 行（PERSONAL 由 trigger 默认建；PROJECT upsert auto=false 占位行作锁目标 + 越权校验）。 */
    private Long ensureScopeRow(Long userId, MemoryConsolidationScopeRequest sr) {
        boolean personal = isPersonalScope(sr);
        String kind = personal ? "PERSONAL" : "PROJECT";
        Long projectId = personal ? null : sr.getProjectId();
        if (!personal) {
            // 项目 scope 须本人可访问（向量 2）
            Set<Long> readable = aclResolver.readableAuthors(projectId, userId);
            if (!readable.contains(userId)) {
                return null;
            }
        }
        OffsetDateTime now = OffsetDateTime.now();
        scopeMapper.upsertScope(userId, kind, projectId, false, now);  // 手动触发不自动改 auto_enabled
        MemoryConsolidationScope row = scopeMapper.findByUserAndScope(userId, kind, projectId);
        return row == null ? null : row.getId();
    }

    /**
     * 列总结入口（设计 §3.4 line119）：{个人} ∪ {本人已加入的 PROJECT scope 行}，标 hasChange/uncoveredCount/autoEnabled。
     * 个人 uncoveredCount = gen_done=true 且无 coverage 的 turn（§3.9 告警阈值用）。
     */
    public List<MemoryConsolidationTargetView> listTargets(Long userId) {
        List<MemoryConsolidationTargetView> out = new ArrayList<>();
        // 个人（恒在）
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
                .build());
        // 已加入的 PROJECT scope 行（用户主动加的自动总结项目；全 ACTIVE 项目枚举走项目成员表，v1 露已配置项）
        List<MemoryConsolidationScope> rows = scopeMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MemoryConsolidationScope>()
                        .eq(MemoryConsolidationScope::getUserId, userId)
                        .eq(MemoryConsolidationScope::getScopeKind, "PROJECT"));
        for (MemoryConsolidationScope r : rows) {
            out.add(MemoryConsolidationTargetView.builder()
                    .scopeKind("PROJECT")
                    .projectId(r.getProjectId())
                    .displayName("项目#" + r.getProjectId())
                    .hasChange(true)
                    .uncoveredCount(0)
                    .autoEnabled(Boolean.TRUE.equals(r.getAutoEnabled()))
                    .build());
        }
        return out;
    }

    /** 锁时长（与 worker 一致）。 */
    private static final int LOCK_MINUTES = 5;
}
