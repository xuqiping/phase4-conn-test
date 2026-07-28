package com.superprogrammer.chat;

import com.superprogrammer.chat.dto.MemoryConsolidationScopeRequest;
import com.superprogrammer.chat.dto.MemoryConsolidationTriggerRequest;
import com.superprogrammer.chat.entity.MemoryConflict;
import com.superprogrammer.chat.entity.MemorySummary;
import com.superprogrammer.chat.mapper.MemoryConflictMapper;
import com.superprogrammer.chat.mapper.MemorySummaryMapper;
import com.superprogrammer.chat.mapper.MemoryTurnMapper;
import com.superprogrammer.chat.service.internal.MemoryConsolidationService;
import com.superprogrammer.chat.service.internal.MemoryConflictResolutionService;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.llm.LlmGateway;
import com.superprogrammer.llm.dto.LlmResponse;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * 计划12 · 迭代 E · 总结 + 冲突全链 IT（实跑 PG16，{@code @MockBean LlmGateway} 避真 LLM）。
 *
 * <p>覆盖出口条件关键项：
 * <ul>
 *   <li>手动总结跑通：triggerManual → summary + coverage 落库；</li>
 *   <li>DISCARD 死循环防护：resolve DISCARD 连带软删 source turns，turns 不再进总结取数；</li>
 *   <li>DISCARD 12h 拒：他人引用方 summarized_at &gt; 12h → FORBIDDEN；</li>
 *   <li>KEEP_BOTH：两方 PENDING summary 回 CLEAN。</li>
 * </ul>
 *
 * <p>{@code @MockBean LlmGateway}：Compressor/Judge 的 LLM 调用全部 stub（避真调计费/抖动）。
 * 真实 E2E（含真 LLM 日期铁律 + 端到端 <2s）留 Phase4。
 */
@SpringBootTest
@Tag("integration")
@Transactional
@Rollback
class MemoryConsolidationFlowIT {

    @Autowired MemoryConsolidationService consolidationService;
    @Autowired MemoryConflictResolutionService conflictService;
    @Autowired MemorySummaryMapper summaryMapper;
    @Autowired MemoryConflictMapper conflictMapper;
    @Autowired MemoryTurnMapper turnMapper;
    @Autowired JdbcTemplate jdbc;

    @MockBean LlmGateway llmGateway;

    // ---- helpers -----------------------------------------------------------

    private Long createUser(String username) {
        return jdbc.queryForObject(
                "INSERT INTO users(username, password) VALUES(?, 'pw') RETURNING id",
                Long.class, username);
    }

    private Long insertTag(Long userId, String topic) {
        return jdbc.queryForObject(
                "INSERT INTO memory_tags(user_id, subject, topic, label) VALUES(?, '我', ?, ?) RETURNING id",
                Long.class, userId, topic, topic);
    }

    private Long insertTurn(Long userId, Long tagId, String year) {
        return jdbc.queryForObject(
                "INSERT INTO memory_turns(user_id, direction, tag_ids, born_personal, gen_done, created_at) " +
                        "VALUES(?, 'INPUT', ?::bigint[], true, true, ?::timestamptz) RETURNING id",
                Long.class, userId, "{" + tagId + "}", year + "-03-15T10:00:00+08:00");
    }

    private Long insertCleanSummary(Long userId, Long tagId, String sourceTurnIds, String l1) {
        return jdbc.queryForObject(
                "INSERT INTO memory_summaries(user_id, project_id, tag_id, l1_summary, source_turn_ids, status, summarized_at) " +
                        "VALUES(?, NULL, ?, ?, ?::bigint[], 'CLEAN', NOW()) RETURNING id",
                Long.class, userId, tagId, l1, sourceTurnIds);
    }

    private void stubCompressorReturnYear(int year) {
        // Compressor 调 chat(LlmRequest, Long userId) → 返 L1 含年份（过日期铁律断言：源 turn 年份须匹配）
        when(llmGateway.chat(any(), anyLong())).thenReturn(
                LlmResponse.builder().content("{\"l1\":\"" + year + " 总结\",\"l2\":\"详述\"}").build());
    }

    // ============================ 1. 手动总结跑通 ============================

    @Test
    void manualSummarizeWritesSummaryAndCoverage() {
        Long uid = createUser("it_flow_sum_" + System.nanoTime());
        Long tag = insertTag(uid, "工作");
        insertTurn(uid, tag, "2026");
        stubCompressorReturnYear(2026);

        MemoryConsolidationScopeRequest sr = new MemoryConsolidationScopeRequest();
        sr.setScopeKind("PERSONAL");
        MemoryConsolidationTriggerRequest req = new MemoryConsolidationTriggerRequest();
        req.setScopes(List.of(sr));

        var result = consolidationService.triggerManual(uid, req);

        assertEquals(1, result.getSummariesWritten(), "写出 1 条 summary");
        List<MemorySummary> summaries = summaryMapper.findByUserAndScope(uid, null);
        assertFalse(summaries.isEmpty(), "summary 落库");
        assertEquals("CLEAN", summaries.get(0).getStatus());
        // coverage 落库（turn 已被该 summary 覆盖）
        Integer cov = jdbc.queryForObject(
                "SELECT COUNT(*) FROM memory_summary_coverage WHERE user_id=? AND tag_id=?",
                Integer.class, uid, tag);
        assertTrue(cov > 0, "coverage 落库");
    }

    // ============================ 2. DISCARD 死循环防护（连带软删 source turns）============================

    @Test
    void discardSoftDeletesSourceTurnsNoRegen() {
        Long uid = createUser("it_flow_discard_" + System.nanoTime());
        Long tag = insertTag(uid, "工作");
        Long t1 = insertTurn(uid, tag, "2026");
        Long t2 = insertTurn(uid, tag, "2026");

        // 造一条 CLEAN summary 引用 t1/t2（待 DISCARD 的目标）
        Long summaryId = insertCleanSummary(uid, tag, "{" + t1 + "," + t2 + "}", "旧总结");
        // 造 PENDING 冲突关联该 summary
        jdbc.update("INSERT INTO memory_conflicts(user_id, tag_id, summary_id, ask_text, status, created_at) " +
                "VALUES(?, ?, ?, '冲突?', 'PENDING', NOW())", uid, tag, summaryId);
        Long conflictId = conflictMapper.findV47PendingByUserAndTag(uid, tag).getId();

        boolean ok = conflictService.resolve(uid, conflictId, "DISCARD");

        assertTrue(ok);
        assertNull(turnMapper.selectById(t1), "source turn t1 被连带软删");
        assertNull(turnMapper.selectById(t2), "source turn t2 被连带软删");
        assertNull(summaryMapper.selectById(summaryId), "冲突 summary 被软删");
        // 死循环防护：turns 软删后不再进总结取数（findPersonalTurnsForConsolidation 不返）
        List<?> recalled = turnMapper.findPersonalTurnsForConsolidation(uid, List.of(tag), "BOTH", null, null, null);
        assertTrue(recalled.isEmpty(), "软删 turns 不进总结取数（防 worker 再生成同冲突）");
    }

    // ============================ 3. DISCARD 12h 拒（他人引用 >12h）============================

    @Test
    void discardRejectsWhenOtherReferenceOlderThan12h() {
        Long self = createUser("it_flow_self_" + System.nanoTime());
        Long other = createUser("it_flow_other_" + System.nanoTime());
        Long tagSelf = insertTag(self, "工作");
        Long tagOther = insertTag(other, "工作");
        Long t1 = insertTurn(self, tagSelf, "2026");

        Long summaryId = insertCleanSummary(self, tagSelf, "{" + t1 + "}", "我的总结");
        jdbc.update("INSERT INTO memory_conflicts(user_id, tag_id, summary_id, ask_text, status, created_at) " +
                "VALUES(?, ?, ?, '冲突?', 'PENDING', NOW())", self, tagSelf, summaryId);
        Long conflictId = conflictMapper.findV47PendingByUserAndTag(self, tagSelf).getId();

        // 他人 summary 引用 t1，13h 前总结 → DISCARD 应被拒
        jdbc.update("INSERT INTO memory_summaries(user_id, project_id, tag_id, l1_summary, source_turn_ids, status, summarized_at) " +
                        "VALUES(?, NULL, ?, '他方总结', ?::bigint[], 'CLEAN', NOW() - INTERVAL '13 hours')",
                other, tagOther, "{" + t1 + "}");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> conflictService.resolve(self, conflictId, "DISCARD"));
        // turns 未被删（拒绝）
        assertNotNull(turnMapper.selectById(t1), "12h 拒 → source turn 不删");
    }

    // ============================ 4. KEEP_BOTH 两方回 CLEAN ============================

    @Test
    void keepBothMarksBothClean() {
        Long uid = createUser("it_flow_both_" + System.nanoTime());
        Long tag = insertTag(uid, "工作");

        // 两条 PENDING summary（同 tag scope）
        Long s1 = jdbc.queryForObject(
                "INSERT INTO memory_summaries(user_id, project_id, tag_id, l1_summary, source_turn_ids, status, summarized_at) " +
                        "VALUES(?, NULL, ?, '旧', '{}', 'PENDING_CONFLICT', NOW()) RETURNING id",
                Long.class, uid, tag);
        Long s2 = jdbc.queryForObject(
                "INSERT INTO memory_summaries(user_id, project_id, tag_id, l1_summary, source_turn_ids, status, summarized_at) " +
                        "VALUES(?, NULL, ?, '新', '{}', 'PENDING_CONFLICT', NOW()) RETURNING id",
                Long.class, uid, tag);
        jdbc.update("INSERT INTO memory_conflicts(user_id, tag_id, summary_id, ask_text, status, created_at) " +
                "VALUES(?, ?, ?, '冲突?', 'PENDING', NOW())", uid, tag, s2);
        Long conflictId = conflictMapper.findV47PendingByUserAndTag(uid, tag).getId();

        boolean ok = conflictService.resolve(uid, conflictId, "KEEP_BOTH");

        assertTrue(ok);
        assertEquals("CLEAN", summaryMapper.selectById(s1).getStatus(), "旧方回 CLEAN");
        assertEquals("CLEAN", summaryMapper.selectById(s2).getStatus(), "新方回 CLEAN");
    }

    // ============================ 5. 非作者不可裁决（向量 6/15）============================

    @Test
    void nonOwnerCannotResolve() {
        Long self = createUser("it_flow_owner_" + System.nanoTime());
        Long attacker = createUser("it_flow_attacker_" + System.nanoTime());
        Long tag = insertTag(self, "工作");
        Long t1 = insertTurn(self, tag, "2026");
        Long summaryId = insertCleanSummary(self, tag, "{" + t1 + "}", "总结");
        jdbc.update("INSERT INTO memory_conflicts(user_id, tag_id, summary_id, ask_text, status, created_at) " +
                "VALUES(?, ?, ?, '冲突?', 'PENDING', NOW())", self, tag, summaryId);
        Long conflictId = conflictMapper.findV47PendingByUserAndTag(self, tag).getId();

        // attacker 越权裁决 → NOT_FOUND（不区分存在性探测）
        assertThrows(BusinessException.class, () -> conflictService.resolve(attacker, conflictId, "KEEP_BOTH"));
        // summary 仍 PENDING（未被改）
        // 注：KEEP_BOTH 不改 status 若无 pendings，此处仅验越权拦截不抛成功
    }
}
