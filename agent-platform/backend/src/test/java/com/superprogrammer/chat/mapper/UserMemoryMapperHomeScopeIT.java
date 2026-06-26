package com.superprogrammer.chat.mapper;

import com.superprogrammer.chat.entity.UserMemory;
import com.superprogrammer.knowledge.AbstractIntegrationTest;
import com.superprogrammer.knowledge.util.HalfVecUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * UserMemoryMapper home+可见性混合 scope（V34）真实 PG 集成测。
 * <p>验 V34 核心契约（取代 V33 scope-orthogonal 唯一索引）：
 * <ul>
 *   <li>跨 home 同 key 共存：global/P1/P2 各一条同 key child_name（不同 home）全部入库；</li>
 *   <li>同 home 同 key 被唯一索引拦截（{@code uk_user_memories_user_key_home}，COALESCE 解 NULL）；</li>
 *   <li>{@code findCleanByHomeKey} home-aware dedup：只返同 home 的 clean 行，不串 scope（修 V33 撞墙 bug 根因）；</li>
 *   <li>{@code insertMemory} 落 home_project_id 列。</li>
 * </ul>
 * H2 跑不了 COALESCE 表达式索引 / halfvec —— 故走 it profile 真 PG。
 */
class UserMemoryMapperHomeScopeIT extends AbstractIntegrationTest {

    @Autowired private UserMemoryMapper mapper;
    @Autowired private JdbcTemplate jdbc;

    private static final long U1 = 880_101L;
    private long p1;
    private long p2;

    @BeforeEach
    void seed() {
        clean();
        jdbc.update("INSERT INTO users (id, username, password) OVERRIDING SYSTEM VALUE VALUES (?,?,?)", U1, "u880101", "x");
        p1 = jdbc.queryForObject(
                "INSERT INTO projects (name, created_by) VALUES (?,?) RETURNING id",
                Long.class, "P1", U1);
        p2 = jdbc.queryForObject(
                "INSERT INTO projects (name, created_by) VALUES (?,?) RETURNING id",
                Long.class, "P2", U1);
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    @Test
    void crossHome_sameKey_allCoexist() {
        // 用户撞墙场景的正面验证：同 key child_name 在 global / P1 / P2 各一条，全部入库成功
        long g = insertHomeMemory(U1, "child_name", null);
        long one = insertHomeMemory(U1, "child_name", p1);
        long two = insertHomeMemory(U1, "child_name", p2);

        // 三条都落库（不同 home → home-aware 唯一索引放行）
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM user_memories WHERE user_id=? AND memory_key=? ORDER BY id",
                Long.class, U1, "child_name");
        assertEquals(List.of(g, one, two), ids, "global/P1/P2 三条同 key 不同 home 共存");
    }

    @Test
    void sameHome_sameKey_blockedByUniqueIndex() {
        insertHomeMemory(U1, "child_name", null);   // global home 第一条
        // 第二条同 home(global) 同 key → 必须撞 uk_user_memories_user_key_home
        assertThrows(org.springframework.dao.DuplicateKeyException.class,
                () -> insertHomeMemory(U1, "child_name", null),
                "同 home 同 key 第二次 INSERT 必撞唯一约束");
        // P1 home 同样：两条同 home(P1) 必撞
        insertHomeMemory(U1, "addr", p1);
        assertThrows(org.springframework.dao.DuplicateKeyException.class,
                () -> insertHomeMemory(U1, "addr", p1),
                "同 home(P1) 同 key 第二次 INSERT 必撞唯一约束");
    }

    @Test
    void findCleanByHomeKey_isHomeScoped() {
        // 三条同 key 不同 home
        long g = insertHomeMemory(U1, "child_name", null);
        long one = insertHomeMemory(U1, "child_name", p1);
        long two = insertHomeMemory(U1, "child_name", p2);

        // home-aware dedup：每个 home 只看到自己那条（修 V33 bug：旧 scope-filtered 在写目标切项目时
        // 看不到 global 老行 → INSERT 撞墙。现按 home 查，home 对齐索引，永不串）
        assertEquals(List.of(g), ids(mapper.findCleanByHomeKey(U1, "child_name", null)),
                "global home 查 → 仅 global 那条");
        assertEquals(List.of(one), ids(mapper.findCleanByHomeKey(U1, "child_name", p1)),
                "P1 home 查 → 仅 P1 那条");
        assertEquals(List.of(two), ids(mapper.findCleanByHomeKey(U1, "child_name", p2)),
                "P2 home 查 → 仅 P2 那条");
        // 不存在的 home → 空（不误返其他 home 的同 key 行）
        assertTrue(mapper.findCleanByHomeKey(U1, "child_name", 999_999L).isEmpty(),
                "未知 home 查 → 空，不串其他 home");
    }

    @Test
    void findCleanByHomeKey_skipsFlagged() {
        // conflict_id 非空的 FLAGGED 行不参与 dedup（唯一索引 WHERE conflict_id IS NULL 对齐）
        long clean = insertHomeMemory(U1, "child_name", null);
        jdbc.update("INSERT INTO memory_conflicts (user_id, new_memory, existing_memory_ids, status) "
                + "VALUES (?,'{}'::jsonb,'{}'::bigint[],'PENDING')", U1);
        Long conflictId = jdbc.queryForObject(
                "SELECT id FROM memory_conflicts WHERE user_id=? ORDER BY id DESC LIMIT 1",
                Long.class, U1);
        // 手插一条同 home 同 key 但带 conflict_id 的 FLAGGED 行（绕唯一索引的 WHERE 条件）
        jdbc.update("""
                INSERT INTO user_memories
                  (user_id, category, memory_key, memory_key_zh, memory_value, source, confidence,
                   block_label, embedding, conflict_id, entities, is_global, home_project_id, created_at, updated_at)
                VALUES (?,?,?,?,'flagged','INFERRED',0.5,'b1',?::halfvec,?,NULL,true,NULL,now(),now())
                """,
                U1, "FACT", "child_name", "x_zh", halfAxis(0), conflictId);
        // dedup 只返 clean 那条，FLAGGED 隐身
        assertEquals(List.of(clean), ids(mapper.findCleanByHomeKey(U1, "child_name", null)),
                "FLAGGED 行不参与 home dedup");
    }

    @Test
    void insertMemory_setsHomeColumn() {
        UserMemory m = baseFact("k1", p1);
        mapper.insertMemory(m, halfAxis(0));
        Long home = jdbc.queryForObject(
                "SELECT home_project_id FROM user_memories WHERE id=?", Long.class, m.getId());
        assertEquals(p1, home, "insertMemory 落 home_project_id = P1");

        UserMemory mGlobal = baseFact("k2", null);
        mapper.insertMemory(mGlobal, halfAxis(0));
        Long homeGlobal = jdbc.queryForObject(
                "SELECT home_project_id FROM user_memories WHERE id=?", Long.class, mGlobal.getId());
        assertNull(homeGlobal, "global 写目标 home_project_id = NULL");
    }

    // ============================ helpers ============================

    private UserMemory baseFact(String key, Long home) {
        UserMemory m = new UserMemory();
        m.setUserId(U1);
        m.setCategory("FACT");
        m.setMemoryKey(key);
        m.setMemoryKeyZh(key + "_zh");
        m.setMemoryValue("v");
        m.setSource("INFERRED");
        m.setConfidence(java.math.BigDecimal.valueOf(0.9));
        m.setBlockLabel("b1");
        m.setIsGlobal(home == null);
        m.setHomeProjectId(home);
        return m;
    }

    /** 单位轴 halfvec（dim0=1）。 */
    private String halfAxis(int axis) {
        float[] v = new float[HalfVecUtil.DIM];
        v[axis] = 1.0f;
        return HalfVecUtil.toHalfVec(v);
    }

    /** 直插一条 clean 记忆带指定 home（绕 mapper，直接验 DB 索引行为）。返 id。 */
    private long insertHomeMemory(long user, String key, Long home) {
        jdbc.update("""
                INSERT INTO user_memories
                  (user_id, category, memory_key, memory_key_zh, memory_value, source, confidence,
                   block_label, embedding, conflict_id, entities, is_global, home_project_id, created_at, updated_at)
                VALUES (?,?,?,?,'v','INFERRED',0.9,'b1',?::halfvec,NULL,NULL,?,?,now(),now())
                """,
                user, "FACT", key, key + "_zh", halfAxis(0), home == null, home);
        return jdbc.queryForObject(
                "SELECT id FROM user_memories WHERE user_id=? AND memory_key=? AND COALESCE(home_project_id,-1)=COALESCE(?, -1)",
                Long.class, user, key, home);
    }

    private List<Long> ids(List<UserMemory> rows) {
        return rows.stream().map(UserMemory::getId).toList();
    }

    private void clean() {
        jdbc.update("DELETE FROM user_memory_projects WHERE memory_id IN (SELECT id FROM user_memories WHERE user_id=?)", U1);
        jdbc.update("DELETE FROM user_memories WHERE user_id=?", U1);
        jdbc.update("DELETE FROM memory_conflicts WHERE user_id=?", U1);
        jdbc.update("DELETE FROM project_members WHERE user_id=?", U1);
        jdbc.update("DELETE FROM projects WHERE created_by=?", U1);
        jdbc.update("DELETE FROM users WHERE id=?", U1);
    }
}
