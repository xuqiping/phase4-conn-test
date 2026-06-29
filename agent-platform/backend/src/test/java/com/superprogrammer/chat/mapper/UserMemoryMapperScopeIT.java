package com.superprogrammer.chat.mapper;

import com.superprogrammer.chat.entity.UserMemory;
import com.superprogrammer.knowledge.AbstractIntegrationTest;
import com.superprogrammer.knowledge.util.HalfVecUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * UserMemoryMapper 项目记忆 scope（V33）真实 PG+pgvector 集成测。
 * <p>验 SCOPE_FILTER 在 keyword / 向量 top-K / 同块 clean / count 四类读路径 + 关联表 round-trip：
 * <ul>
 *   <li>globalOnly / projectOnly / combined / allOff 四种开关组合（扁平对称开关集）；</li>
 *   <li>向量路径走 halfvec {@code &lt;=>}（XML 转义后还原）+ scope 过滤；</li>
 *   <li>跨用户强制隔离（per-user WHERE）；</li>
 *   <li>一条记忆 is_global=true 同时挂项目 = 多 scope 可见（核心特性）。</li>
 * </ul>
 * H2 跑不了 halfvec / BIGINT[] / ILIKE JSONB —— 故 @Tag("integration") 走 it profile。
 */
class UserMemoryMapperScopeIT extends AbstractIntegrationTest {

    @Autowired private UserMemoryMapper mapper;
    @Autowired private JdbcTemplate jdbc;

    private static final long U1 = 880_001L;   // 主测用户
    private static final long U2 = 880_002L;   // 跨用户隔离对照

    private long p1;   // U1 的项目 1
    private long p2;   // U1 的项目 2

    private long mGlobal;   // is_global=true，纯总记忆
    private long mP1;       // is_global=false，挂 P1
    private long mP2;       // is_global=false，挂 P2
    private long mMulti;    // is_global=true 且挂 P1（多 scope 可见）
    private long mOther;    // U2 的总记忆（跨用户对照，同 key）

    @BeforeEach
    void seed() {
        clean();
        jdbc.update("INSERT INTO users (id, username, password) OVERRIDING SYSTEM VALUE VALUES (?,?,?)", U1, "u880001", "x");
        jdbc.update("INSERT INTO users (id, username, password) OVERRIDING SYSTEM VALUE VALUES (?,?,?)", U2, "u880002", "x");
        p1 = jdbc.queryForObject(
                "INSERT INTO projects (name, created_by) VALUES (?,?) RETURNING id",
                Long.class, "P1", U1);
        p2 = jdbc.queryForObject(
                "INSERT INTO projects (name, created_by) VALUES (?,?) RETURNING id",
                Long.class, "P2", U1);

        // U1 四条记忆（key 均含 "shared"，keyword 召回靠 key 锚定 → 让 scope 决定命中）
        // 向量：M_global/M_p1 轴 0（query 轴 0 近），M_p2 轴 1（远，被阈值滤）；M_multi 无向量
        mGlobal = insertMemory(U1, "shared_global", true, halfAxis(0), null);
        mP1 = insertMemory(U1, "shared_p1", false, halfAxis(0), p1);
        mP2 = insertMemory(U1, "shared_p2", false, halfAxis(1), p2);
        mMulti = insertMemory(U1, "shared_multi", true, null, p1);   // global + 挂 P1
        // U2 对照（同 key "shared_other"，跨用户须被滤）
        mOther = insertMemory(U2, "shared_other", true, null, null);
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    @Test
    void findByKeyword_globalOnly_returnsOnlyGlobalRows() {
        List<UserMemory> hits = mapper.findByKeyword(U1, List.of("shared"), true, List.of());
        assertIds(hits, "globalOnly → is_global 行(M_global + M_multi)", mGlobal, mMulti);
    }

    @Test
    void findByKeyword_projectOnly_returnsOnlyProjectRows() {
        List<UserMemory> hits = mapper.findByKeyword(U1, List.of("shared"), false, List.of(p1, p2));
        assertIds(hits, "projectOnly[P1,P2] → M_p1 + M_p2 + M_multi(挂P1)", mP1, mP2, mMulti);
    }

    @Test
    void findByKeyword_projectOnly_singleProject() {
        List<UserMemory> hits = mapper.findByKeyword(U1, List.of("shared"), false, List.of(p1));
        assertIds(hits, "projectOnly[P1] → M_p1 + M_multi，不含 M_p2", mP1, mMulti);
    }

    @Test
    void findByKeyword_combined_globalAndProject() {
        List<UserMemory> hits = mapper.findByKeyword(U1, List.of("shared"), true, List.of(p1));
        assertIds(hits, "combined(global+P1) → M_global + M_multi + M_p1", mGlobal, mMulti, mP1);
    }

    @Test
    void findByKeyword_allOff_returnsNothing() {
        List<UserMemory> hits = mapper.findByKeyword(U1, List.of("shared"), false, List.of());
        assertTrue(hits.isEmpty(), "全关 → SCOPE_FILTER 1=0 → 0 行（不注入）");
    }

    @Test
    void findByKeyword_crossUserNeverReturned() {
        // U1 任何 scope 都不应命中 U2 的 M_other
        List<UserMemory> globalU1 = mapper.findByKeyword(U1, List.of("shared"), true, List.of());
        assertTrue(globalU1.stream().noneMatch(m -> m.getId() == mOther), "U1 不应见 U2 的记忆");
        // U2 globalOnly 只见自己的 M_other
        List<UserMemory> globalU2 = mapper.findByKeyword(U2, List.of("shared"), true, List.of());
        assertIds(globalU2, "U2 globalOnly → 仅 M_other", mOther);
    }

    @Test
    void findCleanByBlock_respectsScope() {
        // 全部 U1 记忆 block=b1 且 clean（conflict_id NULL）
        assertIds(mapper.findCleanByBlock(U1, "b1", true, List.of()),
                "cleanByBlock globalOnly → M_global + M_multi", mGlobal, mMulti);
        assertIds(mapper.findCleanByBlock(U1, "b1", false, List.of(p1, p2)),
                "cleanByBlock[P1,P2] → M_p1+M_p2+M_multi", mP1, mP2, mMulti);
        assertTrue(mapper.findCleanByBlock(U1, "b1", false, List.of()).isEmpty(),
                "cleanByBlock 全关 → 0 行");
    }

    @Test
    void countByScope_respectsScope() {
        assertEquals(2L, mapper.countByScope(U1, new BigDecimal("0.5"), true, List.of()),
                "count globalOnly → M_global + M_multi");
        assertEquals(4L, mapper.countByScope(U1, new BigDecimal("0.5"), true, List.of(p1, p2)),
                "count combined → 全部 4 条 U1 记忆");
        assertEquals(0L, mapper.countByScope(U1, new BigDecimal("0.5"), false, List.of()),
                "count 全关 → 0");
    }

    @Test
    void findTopKByVector_respectsScope() {
        // M_global + M_p1 轴 0；M_p2 轴 1（query 轴 0 → 距离 √2，sim<0 被阈值滤）；M_multi/M_other 无向量
        // query halfAxis(0)：自身距离 0 sim=1.0≥0.5；轴 1 距离 √2 sim<0
        List<UserMemory> globalHits = mapper.findTopKByVector(U1, halfAxis(0), 0.5, 10, true, List.of());
        assertIds(globalHits, "向量 globalOnly → 仅 M_global（有向量且 is_global）", mGlobal);

        List<UserMemory> projHits = mapper.findTopKByVector(U1, halfAxis(0), 0.5, 10, false, List.of(p1));
        assertIds(projHits, "向量 projectOnly[P1] → 仅 M_p1（M_global 被 scope 滤，M_p2 远）", mP1);
    }

    @Test
    void projectAssociation_roundTrip() {
        // M_p1 已挂 P1（seed）；验读取 + 替换（删后重挂 P2）
        assertEquals(List.of(p1), mapper.findProjectIdsByMemory(mP1),
                "M_p1 初始挂 P1");

        mapper.deleteMemoryProjects(mP1);
        assertTrue(mapper.findProjectIdsByMemory(mP1).isEmpty(), "删后空");

        mapper.insertMemoryProjects(mP1, List.of(p2));
        assertEquals(List.of(p2), mapper.findProjectIdsByMemory(mP1), "重挂 P2");

        // 批量查（面板「所属项目」列用，一次查避 N+1）
        List<com.superprogrammer.chat.dto.MemoryProjectRow> rows =
                mapper.findProjectIdsByMemories(List.of(mGlobal, mP1, mP2));
        assertEquals(2, rows.size(), "M_global 无挂载 + M_p1/M_p2 各 1 = 2 行");
    }

    // ============================ helpers ============================

    /** 单位轴 halfvec（dim0=1 其余 0）→ 与自身距离 0、与轴 1 距离 √2≈1.414（sim 1-1.414 <0）。 */
    private String halfAxis(int axis) {
        float[] v = new float[HalfVecUtil.DIM];
        v[axis] = 1.0f;
        return HalfVecUtil.toHalfVec(v);
    }

    /** 插一条 clean 记忆 + 可选挂项目。返 id（按 user+key 回查，避 identity 显式注入坑）。 */
    private long insertMemory(long user, String key, boolean isGlobal, String halfvec, Long project) {
        jdbc.update("""
                INSERT INTO user_memories
                  (user_id, category, memory_key, memory_key_zh, memory_value, source, confidence,
                   block_label, embedding, conflict_id, entities, is_global, created_at, updated_at)
                VALUES (?,?,?,?,'v','INFERRED',0.9,'b1',?::halfvec,NULL,NULL,?,now(),now())
                """,
                user, "FACT", key, key + "_zh", halfvec, isGlobal);
        long id = jdbc.queryForObject(
                "SELECT id FROM user_memories WHERE user_id=? AND memory_key=?",
                Long.class, user, key);
        if (project != null) {
            mapper.insertMemoryProjects(id, List.of(project));
        }
        return id;
    }

    private void assertIds(List<UserMemory> hits, String msg, long... expected) {
        List<Long> got = hits.stream().map(UserMemory::getId).sorted().toList();
        List<Long> want = java.util.Arrays.stream(expected).sorted().boxed().toList();
        assertEquals(want, got, msg);
    }

    private void clean() {
        // FK 安全序：关联 → 记忆 → 成员 → 项目 → 用户（仅清测试高段 id，不碰 seed）
        jdbc.update("DELETE FROM user_memory_projects WHERE memory_id IN "
                + "(SELECT id FROM user_memories WHERE user_id IN (?,?))", U1, U2);
        jdbc.update("DELETE FROM user_memories WHERE user_id IN (?,?)", U1, U2);
        jdbc.update("DELETE FROM project_members WHERE user_id IN (?,?)", U1, U2);
        jdbc.update("DELETE FROM projects WHERE created_by IN (?,?)", U1, U2);
        jdbc.update("DELETE FROM users WHERE id IN (?,?)", U1, U2);
    }
}
