package com.superprogrammer.chat.service.internal;

import com.superprogrammer.chat.dto.MemoryConsolidationScopeRequest;
import com.superprogrammer.chat.entity.MemoryConsolidationScope;
import com.superprogrammer.chat.entity.MemorySummary;
import com.superprogrammer.chat.entity.MemorySummaryCoverage;
import com.superprogrammer.chat.entity.MemoryTurn;
import com.superprogrammer.chat.mapper.MemoryConsolidationScopeMapper;
import com.superprogrammer.chat.mapper.MemorySummaryCoverageMapper;
import com.superprogrammer.chat.mapper.MemorySummaryMapper;
import com.superprogrammer.chat.mapper.MemoryTagMapper;
import com.superprogrammer.chat.mapper.MemoryTurnMapper;
import com.superprogrammer.chat.service.internal.MemoryConsolidationCompressor.CompressedSummary;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 计划12 · E-6 · 总结定时 worker（总体设计 §3.4 自动总结 + §3.8 STALE 重生）。
 * <p>
 * 类 {@code IndexJobWorker}：{@code @Scheduled} 轮询 → 认领（{@code FOR UPDATE SKIP LOCKED} 双节点互斥）
 * → 异步处理（事务外，含 LLM 压缩）→ 释放锁。
 * <p>
 * <b>双节点不双跑</b>：认领即置 {@code locked_until=now+LOCK_MINUTES}，他节点 {@code claimAutoScopes}
 * {@code WHERE locked_until < now} 自然排除（SKIP LOCKED + 时间戳双保险）。
 * <p>
 * <b>幂等</b>：{@code last_run_at >= periodStart} 则不认领（周期内已跑过，防重复压缩 LLM 计费）。
 * <p>
 * <b>gen 关态空跳过</b>：{@code summarizeScope(manual=false)} 不 backfill raw；scope 内周期新增全 raw
 * 时无未覆盖 turn → 不调压缩 LLM（设计 §3.4 line 125 解耦）。
 * <p>
 * <b>STALE 重生</b>：DISCARD/turn 删除级联标 STALE 的 summary，worker 下次轮询按剩余 source_turn_ids
 * 重压缩（设计 §3.8 line 172）。
 *
 * @see MemoryConsolidationTxService#claimAutoScopes 认领（事务内 SKIP LOCKED + markClaimed）
 * @see IndexJobWorker 同款轮询认领范式
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemoryConsolidationWorker {

    private static final int BATCH = 20;
    private static final int LOCK_MINUTES = 5;

    private final MemoryConsolidationTxService txService;
    private final MemoryConsolidationService consolidationService;
    private final MemoryConsolidationCompressor compressor;
    private final MemoryConsolidationScopeMapper scopeMapper;
    private final MemorySummaryMapper summaryMapper;
    private final MemoryTurnMapper turnMapper;
    private final MemoryTagMapper tagMapper;
    private final MemorySummaryCoverageMapper coverageMapper;

    /**
     * 定时自动总结（默认每 10min 轮询认领；周期默认 1 天，{@code last_run_at >= 周期起点} 跳过）。
     */
    @Scheduled(fixedDelayString = "${memory.consolidation.poll-ms:600000}")
    public void pollAuto() {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime periodStart = now.minusDays(1);
        List<MemoryConsolidationScope> claimed;
        try {
            claimed = txService.claimAutoScopes(BATCH, now, periodStart, LOCK_MINUTES);
        } catch (Exception e) {
            log.error("总结 worker 认领失败: {}", e.getMessage(), e);
            return;
        }
        if (claimed.isEmpty()) {
            return;
        }
        log.info("总结 worker 认领 {} 个 scope", claimed.size());
        Set<Long> processedUsers = new HashSet<>();
        for (MemoryConsolidationScope scope : claimed) {
            processedUsers.add(scope.getUserId());
            processScope(scope);
        }
        // STALE 重生：认领触达的用户顺带跑一次（DISCARD 级联标 STALE 的 summary）
        for (Long uid : processedUsers) {
            try {
                regenStaleSummaries(uid);
            } catch (Exception e) {
                log.warn("STALE 重生异常 userId={}: {}", uid, e.getMessage());
            }
        }
    }

    /** 处理单 scope（事务外，含 LLM 压缩）→ 成功/失败释放锁。 */
    private void processScope(MemoryConsolidationScope scope) {
        try {
            MemoryConsolidationScopeRequest req = buildReq(scope);
            consolidationService.summarizeScope(scope.getUserId(), req, false);
            scopeMapper.releaseLockSuccess(scope.getId(), OffsetDateTime.now());
        } catch (Exception e) {
            log.error("总结 scope 失败 userId={} scopeId={}: {}", scope.getUserId(), scope.getId(), e.getMessage(), e);
            scopeMapper.releaseLockFailure(scope.getId());
        }
    }

    /** scope 行 → 取数配置（PERSONAL / PROJECT，默认 SELF 作者 + BOTH 方向）。 */
    private static MemoryConsolidationScopeRequest buildReq(MemoryConsolidationScope scope) {
        MemoryConsolidationScopeRequest req = new MemoryConsolidationScopeRequest();
        boolean personal = "PROJECT".equalsIgnoreCase(scope.getScopeKind()) && scope.getProjectId() != null ? false : true;
        req.setScopeKind(personal ? "PERSONAL" : "PROJECT");
        req.setProjectId(personal ? null : scope.getProjectId());
        req.setAuthorFilter("SELF");
        req.setDirection("BOTH");
        return req;
    }

    /**
     * STALE 重生：本人 status=STALE 的 summary，按剩余 source_turn_ids（已软删的剔）重压缩 →
     * 文本更新 + status=CLEAN；剩余 turn 空 → 软删 summary + 清 coverage。
     */
    void regenStaleSummaries(Long userId) {
        List<MemorySummary> stales = summaryMapper.findStaleByUser(userId);
        if (stales == null || stales.isEmpty()) {
            return;
        }
        for (MemorySummary s : stales) {
            try {
                regenOne(userId, s);
            } catch (Exception e) {
                log.warn("STALE 重生单条异常 userId={} summaryId={}: {}", userId, s.getId(), e.getMessage());
            }
        }
    }

    private void regenOne(Long userId, MemorySummary s) {
        List<Long> source = s.getSourceTurnIds() == null ? List.of() : s.getSourceTurnIds();
        List<MemoryTurn> remaining = source.isEmpty() ? List.of() : turnMapper.findTurnsByIds(source);
        if (remaining.isEmpty()) {
            // 源 turn 全删 → 软删 summary + 清 coverage
            summaryMapper.softDeleteByIds(List.of(s.getId()));
            coverageMapper.deleteBySummaryId(s.getId());
            log.info("STALE 重生：源 turn 全删，软删 summary={}", s.getId());
            return;
        }
        String tagLabel = tagMapper.selectById(s.getTagId()) != null
                ? tagMapper.selectById(s.getTagId()).getLabel() : "总结";
        CompressedSummary cs = compressor.compress(userId, tagLabel, remaining);
        if (cs == null) {
            log.info("STALE 重生压缩失败 summary={} → 保留 STALE 下轮再试", s.getId());
            return;  // 保留 STALE，下轮重试
        }
        summaryMapper.updateTextAndStatus(s.getId(), cs.l1(), cs.l2(), "CLEAN");
        // coverage 按剩余 turns 重建（删旧 + 批量插新）
        coverageMapper.deleteBySummaryId(s.getId());
        List<MemorySummaryCoverage> rows = new ArrayList<>(remaining.size());
        for (MemoryTurn t : remaining) {
            MemorySummaryCoverage c = new MemorySummaryCoverage();
            c.setTurnId(t.getId());
            c.setTagId(s.getTagId());
            c.setSummaryId(s.getId());
            c.setProjectId(s.getProjectId());
            c.setUserId(userId);
            rows.add(c);
        }
        coverageMapper.batchInsert(rows);
        log.info("STALE 重生完成 summary={} remainingTurns={}", s.getId(), remaining.size());
    }
}
