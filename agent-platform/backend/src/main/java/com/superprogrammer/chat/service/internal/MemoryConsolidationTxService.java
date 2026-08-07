package com.superprogrammer.chat.service.internal;

import com.superprogrammer.chat.entity.MemoryConflict;
import com.superprogrammer.chat.entity.MemoryConsolidationScope;
import com.superprogrammer.chat.entity.MemorySummary;
import com.superprogrammer.chat.entity.MemorySummaryCoverage;
import com.superprogrammer.chat.entity.MemoryTurn;
import com.superprogrammer.chat.mapper.MemoryConflictMapper;
import com.superprogrammer.chat.mapper.MemoryConsolidationScopeMapper;
import com.superprogrammer.chat.mapper.MemorySummaryCoverageMapper;
import com.superprogrammer.chat.mapper.MemorySummaryMapper;
import com.superprogrammer.chat.service.internal.MemoryConflictJudge.SummaryConflictResult;
import com.superprogrammer.chat.service.internal.MemoryConsolidationCompressor.CompressedSummary;
import com.superprogrammer.chat.service.internal.MemoryConsolidationService.SummarizeResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 计划12 · E-3 · 总结写库事务服务（同 {@code IndexJobTxService} 先例）。
 * <p>
 * 独立 bean：{@link MemoryConsolidationService}（非事务，含 LLM 压缩）跨 bean 调本类 @Transactional 方法，
 * 经 Spring 代理生效（同类自调绕代理的坑）。事务粒度小：仅 summary + coverage + 冲突落库原子，
 * <b>LLM 压缩在事务外</b>（秒级阻塞+计费，不持 DB 连接/事务）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryConsolidationTxService {

    private final MemorySummaryMapper summaryMapper;
    private final MemorySummaryCoverageMapper coverageMapper;
    private final MemoryConflictMapper conflictMapper;
    private final MemoryConflictJudge conflictJudge;
    private final MemoryConsolidationScopeMapper scopeMapper;

    /**
     * E-6 worker 定时认领（@Transactional：FOR UPDATE SKIP LOCKED 须在事务内，
     * 紧接 markClaimed 置锁同 tx，双节点互斥；事务提交后行锁释放但 locked_until 持久化挡他节点）。
     */
    @Transactional(rollbackFor = Exception.class)
    public List<MemoryConsolidationScope> claimAutoScopes(int limit, OffsetDateTime now, OffsetDateTime periodStart,
                                                          int lockMinutes) {
        List<MemoryConsolidationScope> claimed = scopeMapper.claimAutoScopes(limit, now, periodStart);
        if (claimed.isEmpty()) {
            return List.of();
        }
        OffsetDateTime lockUntil = now.plusMinutes(lockMinutes);
        for (MemoryConsolidationScope s : claimed) {
            scopeMapper.markClaimed(s.getId(), lockUntil);
        }
        return claimed;
    }

    /**
     * 写 summary + coverage + 冲突检测（事务化原子）。
     * <ul>
     *   <li>写前查同 (user,tag,scope) 已有 CLEAN → judge 时序互斥（E-5）；</li>
     *   <li>互斥 → 新 summary 写 PENDING_CONFLICT + 已有也置 PENDING + 插冲突行（summary_id=新）；</li>
     *   <li>并存 / 无已有 → 新 summary 写 CLEAN，coverage 批量写。</li>
     * </ul>
     */
    @Transactional(rollbackFor = Exception.class)
    public void writeSummaryAndCoverage(Long userId, Long scopeProjectId, Long tagId, String tagLabel,
                                        List<MemoryTurn> uncovered, CompressedSummary cs, SummarizeResult result) {
        List<MemorySummary> existing = summaryMapper.findCleanByUserTagScope(userId, tagId, scopeProjectId);
        String initialStatus = "CLEAN";
        String askText = null;
        if (existing != null && !existing.isEmpty()) {
            SummaryConflictResult judge = conflictJudge.judgeSummaryConflict(existing, cs.l1() + " " + cs.l2(), userId);
            if (judge.conflict()) {
                initialStatus = "PENDING_CONFLICT";
                askText = judge.askText();
            }
        }

        MemorySummary s = new MemorySummary();
        s.setUserId(userId);
        s.setProjectId(scopeProjectId);
        s.setTagId(tagId);
        s.setL1Summary(cs.l1());
        s.setL2Detail(cs.l2());
        s.setSourceTurnIds(cs.sourceTurnIds());
        s.setStatus(initialStatus);
        s.setSummarizedAt(OffsetDateTime.now());
        s.setCreatedBy(userId);
        s.setUpdatedBy(userId);
        summaryMapper.insert(s);

        List<MemorySummaryCoverage> rows = new ArrayList<>(uncovered.size());
        for (MemoryTurn t : uncovered) {
            MemorySummaryCoverage c = new MemorySummaryCoverage();
            c.setTurnId(t.getId());
            c.setTagId(tagId);
            c.setSummaryId(s.getId());
            c.setProjectId(scopeProjectId);
            c.setUserId(userId);
            rows.add(c);
        }
        if (!rows.isEmpty()) {
            coverageMapper.batchInsert(rows);
        }

        if ("PENDING_CONFLICT".equals(initialStatus)) {
            if (existing != null) {
                for (MemorySummary ex : existing) {
                    summaryMapper.markStatus(ex.getId(), "PENDING_CONFLICT");
                }
            }
            insertV47Conflict(userId, tagId, s.getId(), askText);
            result.conflictsCreated++;
        }
        result.summariesWritten++;
    }

    /** 插 V47 冲突行（tag_id+summary_id，status=PENDING；created_at 显式置，MemoryConflict 无 autoFill）。 */
    private void insertV47Conflict(Long userId, Long tagId, Long summaryId, String askText) {
        MemoryConflict c = new MemoryConflict();
        c.setUserId(userId);
        c.setTagId(tagId);
        c.setSummaryId(summaryId);
        c.setAskText(askText);
        c.setStatus("PENDING");
        c.setCreatedAt(OffsetDateTime.now());
        conflictMapper.insert(c);
    }
}
