package com.superprogrammer.chat;

import com.superprogrammer.chat.entity.MemoryConflict;
import com.superprogrammer.chat.entity.MemoryTag;
import com.superprogrammer.chat.entity.MemoryTurn;
import com.superprogrammer.chat.mapper.MemoryConflictMapper;
import com.superprogrammer.chat.mapper.MemorySummaryCoverageMapper;
import com.superprogrammer.chat.mapper.MemorySummaryMapper;
import com.superprogrammer.chat.mapper.MemoryTagMapper;
import com.superprogrammer.chat.mapper.MemoryTurnMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 计划12 · 迭代 A · 数据层 IT（scope 隔离六路径 + coverage UNIQUE + halfvec XML + bigint[] 回读 + 新用户钩子）。
 *
 * <p>约定：{@code @Tag("integration")} → surefire 默认排除；跑法：
 * <pre>
 *   mvn test -Dsurefire.excludedGroups= -Dtest=MemoryDataLayerIT \
 *     -DDB_PASSWORD=... -DJWT_SECRET=...
 * </pre>
 * 需 PG16 + pgvector + agent_platform 库（@SpringBootTest 启动即 Flyway 跑 V47 建表）。
 *
 * <p>setup 用 JdbcTemplate 原生 SQL（绕开 MetaObjectHandler 自动填充对安全上下文的依赖）；
 * 断言走被测 mapper 的 scope 读方法——读路径才是 scope 隔离验证的对象。
 *
 * <p>{@code @Transactional}+{@code @Rollback}：每测隔离，trigger 插入随回滚清。
 */
@SpringBootTest
@Tag("integration")
@Transactional
@Rollback
class MemoryDataLayerIT {

    @Autowired MemoryTurnMapper turnMapper;
    @Autowired MemorySummaryMapper summaryMapper;
    @Autowired MemorySummaryCoverageMapper coverageMapper;
    @Autowired MemoryConflictMapper conflictMapper;
    @Autowired MemoryTagMapper tagMapper;
    @Autowired JdbcTemplate jdbc;

    // ---- helpers ------------------------------------------------------------

    /** 建一个 throwaway user，返回 id。trigger 自动插 PERSONAL consolidation_scope。 */
    private Long createUser(String username) {
        return jdbc.queryForObject(
                "INSERT INTO users(username, password) VALUES(?, 'pw') RETURNING id",
                Long.class, username);
    }

    private Long insertTag(Long userId, String subject, String topic) {
        return jdbc.queryForObject(
                "INSERT INTO memory_tags(user_id, subject, topic, label) VALUES(?,?,?,?) RETURNING id",
                Long.class, userId, subject, topic, topic);
    }

    /** 建一条 turn（gen_done=true 默认）并回填。projectIdsStr 形如 "{10,20}" 或 "{}"。 */
    private Long insertTurn(Long userId, String direction, String projectIdsStr, boolean bornPersonal) {
        return jdbc.queryForObject(
                "INSERT INTO memory_turns(user_id, direction, project_ids, born_personal, gen_done) " +
                        "VALUES(?, ?, ?::bigint[], ?, true) RETURNING id",
                Long.class, userId, direction, projectIdsStr, bornPersonal);
    }

    // ---- 1. 新用户钩子：trigger 默认插 PERSONAL scope ------------------------

    @Test
    void newUserTriggerInsertsPersonalConsolidationScope() {
        Long uid = createUser("it_trigger_" + System.nanoTime());
        Integer cnt = jdbc.queryForObject(
                "SELECT count(*) FROM memory_consolidation_scopes " +
                        "WHERE user_id=? AND scope_kind='PERSONAL' AND project_id IS NULL AND auto_enabled",
                Integer.class, uid);
        assertEquals(1, cnt, "新用户应自动插一条 PERSONAL 自动总结 scope");
    }

    // ---- 2. BIGINT[] 写入回读（显式 typeHandler，绕开 LambdaUpdateWrapper 坑）----

    @Test
    void bigIntArrayRoundtripViaExplicitTypeHandler() {
        Long uid = createUser("it_arr_" + System.nanoTime());
        Long tid = insertTurn(uid, "INPUT", "{}", true);
        // updateProjectIds 走 XML 显式 typeHandler（V33 教训规避点）
        turnMapper.updateProjectIds(tid, List.of(10L, 20L, 30L));
        MemoryTurn reloaded = turnMapper.selectById(tid);
        assertNotNull(reloaded);
        assertEquals(List.of(10L, 20L, 30L), reloaded.getProjectIds(), "BIGINT[] 经显式 typeHandler 写入应可回读等值");
    }

    // ---- 3. coverage UNIQUE NULLS NOT DISTINCT 拦截 -------------------------

    @Test
    void coverageUniqueNullsNotDistinctBlocksDuplicatePersonalScope() {
        Long uid = createUser("it_uniq_" + System.nanoTime());
        Long tagId = insertTag(uid, "我", "居住");
        Long tid = insertTurn(uid, "INPUT", "{}", true);
        // 第一行 project_id=NULL（个人 scope）——成功
        jdbc.update("INSERT INTO memory_summary_coverage(turn_id, tag_id, project_id, user_id) VALUES(?,?,NULL,?)",
                tid, tagId, uid);
        // 第二行同 (turn,tag,user,project_id=NULL) ——应被 UNIQUE NULLS NOT DISTINCT 拦截
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () ->
                jdbc.update("INSERT INTO memory_summary_coverage(turn_id, tag_id, project_id, user_id) VALUES(?,?,NULL,?)",
                        tid, tagId, uid),
                "个人 scope(project_id=NULL) 第二行应被 NULLS NOT DISTINCT UNIQUE 拦截");
    }

    // ---- 4-6. scope 隔离：流水账 SCOPE_FILTER 三路径 -------------------------

    @Test
    void scope_ownPersonalTurnVisibleToSelf() {
        Long a = createUser("it_a_" + System.nanoTime());
        Long tid = insertTurn(a, "INPUT", "{}", true);
        List<MemoryTurn> visible = turnMapper.findVisibleTurns(a, List.of());
        assertTrue(visible.stream().anyMatch(t -> t.getId().equals(tid)),
                "自己的个人出身 turn 对自己应可见");
    }

    @Test
    void scope_otherUserPersonalTurnInvisibleEvenWithAccessibleProjects() {
        Long a = createUser("it_a2_" + System.nanoTime());
        Long b = createUser("it_b2_" + System.nanoTime());
        Long tid = insertTurn(a, "INPUT", "{}", true);  // A 的个人私有
        // 即便给 B 一堆 accessible 项目，A 的个人 turn（project_ids 空）也命中不了
        List<MemoryTurn> visibleToB = turnMapper.findVisibleTurns(b, List.of(999L));
        assertFalse(visibleToB.stream().anyMatch(t -> t.getId().equals(tid)),
                "他人个人私有 turn 命中不了（project_ids 空集，不在 accessible 内）");
    }

    @Test
    void scope_otherUserProjectTurnVisibleOnlyWhenAccessible() {
        Long a = createUser("it_a3_" + System.nanoTime());
        Long b = createUser("it_b3_" + System.nanoTime());
        Long c = createUser("it_c3_" + System.nanoTime());
        Long projX = 5001L;
        Long tid = insertTurn(a, "INPUT", "{" + projX + "}", false);  // A 纯项目出身挂 X
        // B 持有 accessible=[X] → 可见 A 这条
        List<MemoryTurn> visibleToB = turnMapper.findVisibleTurns(b, List.of(projX));
        assertTrue(visibleToB.stream().anyMatch(t -> t.getId().equals(tid)),
                "accessible 含该项目时他人项目 turn 应可见");
        // C 持 accessible=[] → 不可见
        List<MemoryTurn> visibleToC = turnMapper.findVisibleTurns(c, List.of());
        assertFalse(visibleToC.stream().anyMatch(t -> t.getId().equals(tid)),
                "accessible 不含该项目时他人项目 turn 不可见");
    }

    // ---- 7. scope 隔离：总结恒只读自己 -------------------------------------

    @Test
    void summaryAlwaysScopedToSelf() {
        Long a = createUser("it_sum_a_" + System.nanoTime());
        Long b = createUser("it_sum_b_" + System.nanoTime());
        Long tagId = insertTag(a, "我", "爱好");
        Long sid = jdbc.queryForObject(
                "INSERT INTO memory_summaries(user_id, project_id, tag_id, l1_summary, status) " +
                        "VALUES(?, NULL, ?, '爱爬山', 'CLEAN') RETURNING id",
                Long.class, a, tagId);
        // A 自己读得到
        assertTrue(summaryMapper.findByUserAndScope(a, null).stream().anyMatch(s -> s.getId().equals(sid)),
                "作者本人应读到自己的个人 summary");
        // B 读不到 A 的 summary（他人总结不可见，防污染）
        assertFalse(summaryMapper.findByUserAndScope(b, null).stream().anyMatch(s -> s.getId().equals(sid)),
                "他人总结不可见（防污染）");
    }

    // ---- 8. scope 隔离：冲突强制 user_id ------------------------------------

    @Test
    void conflictScopedToUser() {
        Long a = createUser("it_conf_a_" + System.nanoTime());
        Long b = createUser("it_conf_b_" + System.nanoTime());
        Long cid = jdbc.queryForObject(
                "INSERT INTO memory_conflicts(user_id, status, created_at) VALUES(?, 'PENDING', now()) RETURNING id",
                Long.class, a);
        assertTrue(conflictMapper.findByUser(a).stream().anyMatch(c -> c.getId().equals(cid)),
                "作者本人应读到自己的冲突");
        assertFalse(conflictMapper.findByUser(b).stream().anyMatch(c -> c.getId().equals(cid)),
                "他人冲突不可见（向量 6）");
    }

    // ---- 8b. scope 隔离：覆盖恒只认 user_id=self ----------------------------

    @Test
    void coverageScopedToSelf() {
        Long a = createUser("it_cov_a_" + System.nanoTime());
        Long b = createUser("it_cov_b_" + System.nanoTime());
        Long tagId = insertTag(a, "我", "爱好");
        Long tid = insertTurn(a, "INPUT", "{}", true);
        jdbc.update("INSERT INTO memory_summary_coverage(turn_id, tag_id, project_id, user_id) VALUES(?,?,NULL,?)",
                tid, tagId, a);
        // A 查自己覆盖 turn 集 → 命中
        assertFalse(coverageMapper.findByUserAndTurns(a, List.of(tid)).isEmpty(),
                "作者本人覆盖行应可读");
        // B 查同样 turn 集 → 空（user_id=self 过滤）
        assertTrue(coverageMapper.findByUserAndTurns(b, List.of(tid)).isEmpty(),
                "他人覆盖行不可读（召回只认 user_id=self）");
    }

    // ---- 9. halfvec XML 启动期无解析异常 + 算子可执行 ------------------------
    // context 启动 = 所有 mapper XML 已解析无异常（含 &lt;=&gt; 转义）。本测再实跑一次
    // findNearestByAnchor（含 <=> 算子），证 mapped statement 可执行、算子转义正确。

    @Test
    void halfvecAnchorQueryExecutesWithoutError() {
        Long uid = createUser("it_hv_" + System.nanoTime());
        Long tagId = insertTag(uid, "我", "居住");
        // 用 PG repeat 造 2048 维零向量，灌进 anchor_embedding
        String zeroVec2048 = "[" + "0,".repeat(2047) + "0]";
        jdbc.update("UPDATE memory_tags SET anchor_embedding = ?::halfvec WHERE id=?", zeroVec2048, tagId);
        // 查询向量同维零向量——应命中刚灌的 tag（&lt;=&gt; 距离 0）
        List<MemoryTag> nearest = tagMapper.findNearestByAnchor(uid, zeroVec2048, 5);
        assertFalse(nearest.isEmpty(), "halfvec &lt;=&gt; 查询应能命中已灌 embedding 的 tag");
        assertEquals(tagId, nearest.get(0).getId());
    }
}
