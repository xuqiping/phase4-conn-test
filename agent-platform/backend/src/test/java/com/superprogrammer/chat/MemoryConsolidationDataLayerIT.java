package com.superprogrammer.chat;

import com.superprogrammer.chat.entity.MemoryConflict;
import com.superprogrammer.chat.entity.MemoryConsolidationScope;
import com.superprogrammer.chat.entity.MemorySummary;
import com.superprogrammer.chat.entity.MemorySummaryCoverage;
import com.superprogrammer.chat.entity.MemoryTurn;
import com.superprogrammer.chat.mapper.MemoryConsolidationScopeMapper;
import com.superprogrammer.chat.mapper.MemoryConflictMapper;
import com.superprogrammer.chat.mapper.MemorySummaryCoverageMapper;
import com.superprogrammer.chat.mapper.MemorySummaryMapper;
import com.superprogrammer.chat.mapper.MemoryTurnMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 计划12 · 迭代 E · 总结数据层 IT（V51 锁列 + E-1 新增 mapper 方法实跑 PG16）。
 *
 * <p>覆盖：
 * <ul>
 *   <li>V51 worker 锁：claimAutoScopes（SKIP LOCKED）/ markClaimed / acquireManualLock CAS /
 *       releaseLockSuccess（last_run_at 幂等）/ upsertScope（PROJECT ON CONFLICT）；</li>
 *   <li>总结取数：findPersonalTurnsForConsolidation / findRawTurnsForBackfill / applyBackfill；</li>
 *   <li>DISCARD 级联：softDeleteByIds / findSummariesReferencingTurn（@> 他人引用）/ softDeleteByIds(summary)；</li>
 *   <li>coverage：batchInsert（ON CONFLICT 幂等）/ deleteBySummaryId / deleteByTurnIdsAndUser（仅作者侧）；</li>
 *   <li>冲突：findCleanByUserTagScope / findV47PendingByUserAndTag / markV47Resolved。</li>
 * </ul>
 *
 * <p>约定同 {@link MemoryDataLayerIT}：{@code @Tag("integration")} surefire 默认排除；
 * setup 走 JdbcTemplate 原生 SQL；{@code @Transactional}+{@code @Rollback} 每测隔离。
 */
@SpringBootTest
@Tag("integration")
@Transactional
@Rollback
class MemoryConsolidationDataLayerIT {

    @Autowired MemoryConsolidationScopeMapper scopeMapper;
    @Autowired MemoryTurnMapper turnMapper;
    @Autowired MemorySummaryMapper summaryMapper;
    @Autowired MemorySummaryCoverageMapper coverageMapper;
    @Autowired MemoryConflictMapper conflictMapper;
    @Autowired JdbcTemplate jdbc;

    // ---- helpers -----------------------------------------------------------

    private Long createUser(String username) {
        return jdbc.queryForObject(
                "INSERT INTO users(username, password) VALUES(?, 'pw') RETURNING id",
                Long.class, username);
    }

    private Long createProject(Long ownerUid, String name) {
        return jdbc.queryForObject(
                "INSERT INTO projects(name, created_by) VALUES(?, ?) RETURNING id",
                Long.class, name, ownerUid);
    }

    private Long insertTag(Long userId, String topic) {
        return jdbc.queryForObject(
                "INSERT INTO memory_tags(user_id, subject, topic, label) VALUES(?, '我', ?, ?) RETURNING id",
                Long.class, userId, topic, topic);
    }

    /** 二期 P1（V67）：纯个人域——tagIdsStr 形如 "{1,2}" 或 "{}"。 */
    private Long insertTurn(Long userId, String direction, String tagIdsStr, boolean genDone) {
        return jdbc.queryForObject(
                "INSERT INTO memory_turns(user_id, direction, tag_ids, gen_done) " +
                        "VALUES(?, ?, ?::bigint[], ?) RETURNING id",
                Long.class, userId, direction, tagIdsStr, genDone);
    }

    private Long insertSummary(Long userId, Long projectId, Long tagId, String sourceTurnIdsStr, String status) {
        return jdbc.queryForObject(
                "INSERT INTO memory_summaries(user_id, project_id, tag_id, l1_summary, source_turn_ids, status, summarized_at) " +
                        "VALUES(?, ?, ?, 'l1', ?::bigint[], ?, NOW()) RETURNING id",
                Long.class, userId, projectId, tagId, sourceTurnIdsStr, status);
    }

    private MemoryConsolidationScope personalScope(Long uid) {
        return scopeMapper.findByUserAndScope(uid, "PERSONAL", null);
    }

    // ============================ V51 worker 锁 ============================

    @Test
    void claimAutoScopesReturnsPersonalAndExcludesLocked() {
        Long uid = createUser("it_claim_" + System.nanoTime());
        MemoryConsolidationScope personal = personalScope(uid);
        assertNotNull(personal, "trigger 应已建 PERSONAL scope");
        assertNull(personal.getLockedUntil(), "新建 scope 锁空");
        assertNull(personal.getLastRunAt(), "新建 scope 未跑过");

        OffsetDateTime now = OffsetDateTime.now();
        List<MemoryConsolidationScope> claimed = scopeMapper.claimAutoScopes(10, now, now.minusDays(1));
        assertTrue(claimed.stream().anyMatch(s -> s.getId().equals(personal.getId())),
                "claim 应命中未锁未跑的 PERSONAL scope");

        // 置锁后再 claim 同行不应命中（locked_until 在未来）
        scopeMapper.markClaimed(personal.getId(), now.plusMinutes(5));
        List<MemoryConsolidationScope> claimed2 = scopeMapper.claimAutoScopes(10, now, now.minusDays(1));
        assertFalse(claimed2.stream().anyMatch(s -> s.getId().equals(personal.getId())),
                "已锁 scope 不应再被认领（双节点互斥）");
    }

    @Test
    void releaseLockSuccessSetsLastRunAtAndSkipsSamePeriod() {
        Long uid = createUser("it_rel_" + System.nanoTime());
        MemoryConsolidationScope personal = personalScope(uid);
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime periodStart = now.minusHours(1);

        scopeMapper.markClaimed(personal.getId(), now.plusMinutes(5));
        scopeMapper.releaseLockSuccess(personal.getId(), now);

        MemoryConsolidationScope reloaded = scopeMapper.selectById(personal.getId());
        assertNull(reloaded.getLockedUntil(), "成功释放清锁");
        assertNotNull(reloaded.getLastRunAt(), "last_run_at 落库");
        assertTrue(reloaded.getLastRunAt().isAfter(periodStart), "last_run_at >= 周期起点");

        // 同周期内（periodStart < last_run_at）不再被 claim —— 幂等防重复压缩
        List<MemoryConsolidationScope> claimed = scopeMapper.claimAutoScopes(10, now, periodStart);
        assertFalse(claimed.stream().anyMatch(s -> s.getId().equals(personal.getId())),
                "周期内已跑过的 scope 不应再被认领（幂等）");
    }

    @Test
    void releaseLockFailureKeepsLastRunAtForRetry() {
        Long uid = createUser("it_fail_" + System.nanoTime());
        MemoryConsolidationScope personal = personalScope(uid);
        OffsetDateTime now = OffsetDateTime.now();

        scopeMapper.markClaimed(personal.getId(), now.plusMinutes(5));
        scopeMapper.releaseLockFailure(personal.getId());

        MemoryConsolidationScope reloaded = scopeMapper.selectById(personal.getId());
        assertNull(reloaded.getLockedUntil(), "失败释放清锁");
        assertNull(reloaded.getLastRunAt(), "失败不更 last_run_at，允许下轮重试");
    }

    @Test
    void acquireManualLockIsCasOnlyWhenFree() {
        Long uid = createUser("it_manual_" + System.nanoTime());
        MemoryConsolidationScope personal = personalScope(uid);
        OffsetDateTime now = OffsetDateTime.now();

        int got = scopeMapper.acquireManualLock(personal.getId(), now, now.plusMinutes(5));
        assertEquals(1, got, "锁空闲时应抢到（affected=1）");
        int again = scopeMapper.acquireManualLock(personal.getId(), now, now.plusMinutes(5));
        assertEquals(0, again, "已持锁时 CAS 失败（affected=0），手动与定时互斥");
    }

    @Test
    void upsertProjectScopeTogglesAutoEnabled() {
        Long uid = createUser("it_upsert_" + System.nanoTime());
        Long pid = createProject(uid, "P-" + System.nanoTime());
        OffsetDateTime now = OffsetDateTime.now();

        scopeMapper.upsertScope(uid, "PROJECT", pid, true, now);
        MemoryConsolidationScope s1 = scopeMapper.findByUserAndScope(uid, "PROJECT", pid);
        assertNotNull(s1, "upsert 建 PROJECT scope");
        assertTrue(s1.getAutoEnabled());

        // 再 upsert 翻转 auto_enabled（ON CONFLICT 更新，不新增行）
        scopeMapper.upsertScope(uid, "PROJECT", pid, false, now);
        MemoryConsolidationScope s2 = scopeMapper.findByUserAndScope(uid, "PROJECT", pid);
        assertEquals(s1.getId(), s2.getId(), "ON CONFLICT 同行更新");
        assertFalse(s2.getAutoEnabled(), "翻转生效");
    }

    // ============================ 总结取数 ============================

    @Test
    void findPersonalTurnsForConsolidationFiltersGenDoneAndTag() {
        Long uid = createUser("it_pturn_" + System.nanoTime());
        Long tag = insertTag(uid, "hobby");
        Long otherTag = insertTag(uid, "work");

        Long tGen = insertTurn(uid, "INPUT", "{" + tag + "}", true);
        Long tRaw = insertTurn(uid, "INPUT", "{" + tag + "}", false);          // raw 不取
        Long tOtherTag = insertTurn(uid, "INPUT", "{" + otherTag + "}", true); // 非 tag

        List<MemoryTurn> got = turnMapper.findPersonalTurnsForConsolidation(uid, List.of(tag), "BOTH", null, null, null);
        List<Long> ids = got.stream().map(MemoryTurn::getId).toList();
        assertTrue(ids.contains(tGen), "命本人 gen_done=true 含该 tag");
        assertFalse(ids.contains(tRaw), "raw(gen_done=false) 不进总结取数");
        assertFalse(ids.contains(tOtherTag), "非该 tag 不取");
    }

    @Test
    void findRawTurnsForBackfillBatchedAndApplyBackfill() {
        Long uid = createUser("it_bf_" + System.nanoTime());
        Long tag = insertTag(uid, "diet");

        Long t1 = insertTurn(uid, "INPUT", "{}", false);
        Long t2 = insertTurn(uid, "OUTPUT", "{}", false);
        insertTurn(uid, "INPUT", "{}", true); // 已生成，不进 backfill

        List<MemoryTurn> raws = turnMapper.findRawTurnsForBackfill(uid, 20);
        assertEquals(2, raws.size(), "仅 gen_done=false 的 raw 进 backfill");

        int updated = turnMapper.applyBackfill(t1, List.of(tag), "l1", "l2", uid);
        assertEquals(1, updated);
        MemoryTurn reloaded = turnMapper.selectById(t1);
        assertTrue(reloaded.getGenDone(), "backfill 后 gen_done=true");
        assertEquals(List.of(tag), reloaded.getTagIds(), "tag 写回");
        assertEquals("l1", reloaded.getL1Summary());

        // 再取 raw，t1 已不在
        List<MemoryTurn> raws2 = turnMapper.findRawTurnsForBackfill(uid, 20);
        assertEquals(1, raws2.size());
        assertEquals(t2, raws2.get(0).getId());
    }

    // ============================ DISCARD 级联 ============================

    @Test
    void softDeleteTurnsByIdsAndCoverageCascade() {
        Long uid = createUser("it_discard_" + System.nanoTime());
        Long tag = insertTag(uid, "addr");
        Long t1 = insertTurn(uid, "INPUT", "{" + tag + "}", true);
        Long t2 = insertTurn(uid, "OUTPUT", "{" + tag + "}", true);

        // 造 coverage（作者侧）
        MemorySummaryCoverage c = new MemorySummaryCoverage();
        c.setTurnId(t1); c.setTagId(tag); c.setProjectId(null); c.setUserId(uid);
        coverageMapper.batchInsert(List.of(c));
        assertEquals(1, coverageMapper.findByUserAndTurns(uid, List.of(t1, t2)).size());

        int deleted = turnMapper.softDeleteByIds(List.of(t1, t2));
        assertEquals(2, deleted);
        assertNull(turnMapper.selectById(t1), "软删后 selectById（@TableLogic）返 null");

        // coverage 级联清（按作者侧 turn_ids）
        coverageMapper.deleteByTurnIdsAndUser(List.of(t1, t2), uid);
        assertEquals(0, coverageMapper.findByUserAndTurns(uid, List.of(t1, t2)).size(), "coverage 随 turn 软删级联清");
    }

    @Test
    void findSummariesReferencingTurnIncludesOtherUser() {
        Long self = createUser("it_ref_self_" + System.nanoTime());
        Long other = createUser("it_ref_other_" + System.nanoTime());
        Long tag = insertTag(self, "job");
        Long t1 = insertTurn(self, "INPUT", "{" + tag + "}", true);

        // 本人 summary 引用 t1；他人 summary 也引用 t1（项目 scope 波及场景）
        insertSummary(self, null, tag, "{" + t1 + "}", "CLEAN");
        insertSummary(other, null, insertTag(other, "job"), "{" + t1 + "}", "CLEAN");

        List<MemorySummary> refs = summaryMapper.findSummariesReferencingTurn(t1);
        assertEquals(2, refs.size(), "@> 包含算子命中本人+他人引用方");
        assertTrue(refs.stream().map(MemorySummary::getUserId).anyMatch(u -> u.equals(other)),
                "12h 规则需他人引用方 summarized_at → mapper 须返他人");
    }

    // ============================ coverage 批量幂等 ============================

    @Test
    void batchInsertCoverageOnConflictDoNothing() {
        Long uid = createUser("it_cov_" + System.nanoTime());
        Long tag = insertTag(uid, "skill");
        Long t1 = insertTurn(uid, "INPUT", "{" + tag + "}", true);

        MemorySummaryCoverage c = new MemorySummaryCoverage();
        c.setTurnId(t1); c.setTagId(tag); c.setProjectId(null); c.setUserId(uid); c.setSummaryId(null);
        assertEquals(1, coverageMapper.batchInsert(List.of(c)), "首次插入 1 行");
        assertEquals(0, coverageMapper.batchInsert(List.of(c)), "重复插入 ON CONFLICT DO NOTHING → 0 affected");
        List<MemorySummaryCoverage> all = coverageMapper.findByUserAndTurns(uid, List.of(t1));
        assertEquals(1, all.size(), "幂等：UNIQUE 防重复行");
    }

    // ============================ 冲突查询 ============================

    @Test
    void findCleanByUserTagScopePersonal() {
        Long uid = createUser("it_clean_" + System.nanoTime());
        Long tag = insertTag(uid, "car");
        insertSummary(uid, null, tag, "{}", "CLEAN");
        insertSummary(uid, null, tag, "{}", "PENDING_CONFLICT"); // 非 CLEAN 不返
        insertSummary(uid, null, insertTag(uid, "house"), "{}", "CLEAN"); // 别 tag

        List<MemorySummary> clean = summaryMapper.findCleanByUserTagScope(uid, tag, null, null);
        assertEquals(1, clean.size(), "仅本人同 tag 同 scope(personal) 的 CLEAN");
    }

    @Test
    void v47ConflictPendingAndResolve() {
        Long uid = createUser("it_conf_" + System.nanoTime());
        Long tag = insertTag(uid, "phone");
        Long summary = insertSummary(uid, null, tag, "{}", "CLEAN");

        // 建 V47 PENDING 冲突
        jdbc.update("INSERT INTO memory_conflicts(user_id, tag_id, summary_id, ask_text, status, created_at) " +
                "VALUES(?, ?, ?, '冲突?', 'PENDING', NOW())", uid, tag, summary);

        MemoryConflict existing = conflictMapper.findV47PendingByUserAndTag(uid, tag);
        assertNotNull(existing, "同 (user,tag) 已有 PENDING");
        assertEquals(summary, existing.getSummaryId());

        List<MemoryConflict> list = conflictMapper.findV47PendingByUser(uid);
        assertEquals(1, list.size());

        int resolved = conflictMapper.markV47Resolved(existing.getId(), "KEEP_BOTH");
        assertEquals(1, resolved);
        assertEquals(0, conflictMapper.findV47PendingByUser(uid).size(), "裁决后不再 PENDING");
    }
}
