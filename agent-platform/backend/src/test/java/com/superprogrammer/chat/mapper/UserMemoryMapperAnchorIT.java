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
 * V38 anchor 召回 mapper 集成测（真实 PG16 + pgvector + halfvec）。
 * <p>验 {@link UserMemoryMapper#findTopKByAnchor} / {@link UserMemoryMapper#findAnchorBm25}：
 * <ul>
 *   <li>anchor 向量 top-K 命中（{@code anchor_embedding <=>}，HNSW 走起）+ 阈值滤；</li>
 *   <li>anchor BM25 命中（{@code anchor_tokens_tsv} 上 jieba token OR + ts_rank）；</li>
 *   <li>scope 四开关组合（includeGlobal × projectIds，复用 SCOPE_FILTER）；</li>
 *   <li>{@code <=>} 在 MyBatis {@code <script>} XML 转义 {@code &lt;=>} 不崩（V33 教训）。</li>
 * </ul>
 * H2 跑不了 halfvec / HNSW / tsvector @@ —— @Tag("integration") 走 it profile。
 */
class UserMemoryMapperAnchorIT extends AbstractIntegrationTest {

    @Autowired private UserMemoryMapper mapper;
    @Autowired private JdbcTemplate jdbc;

    private static final long U1 = 881_001L;
    private static final long U2 = 881_002L;

    private long p1;
    private long p2;
    private long mGlobal;   // anchor 轴 0 + tokens "家庭 配偶"，is_global
    private long mP1;       // anchor 轴 0 + tokens "工作 公司"，挂 P1
    private long mP2;       // anchor 轴 1（远，被阈值滤），挂 P2
    private long mMulti;    // is_global + 挂 P1，但无 anchor（粗筛须跳过 null anchor）
    private long mOther;    // U2 对照

    @BeforeEach
    void seed() {
        clean();
        jdbc.update("INSERT INTO users (id, username, password) OVERRIDING SYSTEM VALUE VALUES (?,?,?)", U1, "u881001", "x");
        jdbc.update("INSERT INTO users (id, username, password) OVERRIDING SYSTEM VALUE VALUES (?,?,?)", U2, "u881002", "x");
        p1 = jdbc.queryForObject("INSERT INTO projects (name, created_by) VALUES (?,?) RETURNING id", Long.class, "AP1", U1);
        p2 = jdbc.queryForObject("INSERT INTO projects (name, created_by) VALUES (?,?) RETURNING id", Long.class, "AP2", U1);

        mGlobal = insertAnchor(U1, "shared_global", true, halfAxis(0), "家庭 配偶 妻子", null);
        mP1 = insertAnchor(U1, "shared_p1", false, halfAxis(0), "工作 公司 职位", p1);
        mP2 = insertAnchor(U1, "shared_p2", false, halfAxis(1), "健康 过敏 病史", p2);
        mMulti = insertAnchor(U1, "shared_multi", true, null, null, p1);   // 无 anchor → 粗筛跳过
        mOther = insertAnchor(U2, "shared_other", true, halfAxis(0), "家庭 配偶", null);
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    @Test
    void findTopKByAnchor_respectsScope() {
        // query 轴 0：mGlobal/mP1 自身距离 0 sim=1.0≥0.5；mP2 轴 1 sim<0 被阈值滤；mMulti 无 anchor 跳过
        List<UserMemory> globalHits = mapper.findTopKByAnchor(U1, halfAxis(0), 0.5, 10, true, List.of());
        assertIds(globalHits, "anchor 向量 globalOnly → 仅 mGlobal（有 anchor 且 is_global）", mGlobal);

        List<UserMemory> projHits = mapper.findTopKByAnchor(U1, halfAxis(0), 0.5, 10, false, List.of(p1));
        assertIds(projHits, "anchor 向量 projectOnly[P1] → 仅 mP1（mGlobal 被 scope 滤，mP2 远，mMulti 无 anchor）", mP1);
    }

    @Test
    void findTopKByAnchor_thresholdFiltersFarVectors() {
        // 阈值 0.99：连自身 sim=1.0 也过，但 mP2 轴 1 sim<0 滤掉；globalOnly 仍只有 mGlobal
        List<UserMemory> hits = mapper.findTopKByAnchor(U1, halfAxis(0), 0.99, 10, true, List.of());
        assertIds(hits, "阈值 0.99 仍命中自身（sim=1.0）", mGlobal);
    }

    @Test
    void findTopKByAnchor_allOff_returnsNothing() {
        assertTrue(mapper.findTopKByAnchor(U1, halfAxis(0), 0.5, 10, false, List.of()).isEmpty(),
                "全关 → SCOPE_FILTER 1=0 → 0 行");
    }

    @Test
    void findTopKByAnchor_crossUserNeverReturned() {
        List<UserMemory> u1 = mapper.findTopKByAnchor(U1, halfAxis(0), 0.5, 10, true, List.of());
        assertTrue(u1.stream().noneMatch(m -> m.getId() == mOther), "U1 不应见 U2 的 mOther");
        List<UserMemory> u2 = mapper.findTopKByAnchor(U2, halfAxis(0), 0.5, 10, true, List.of());
        assertIds(u2, "U2 globalOnly → 仅 mOther", mOther);
    }

    @Test
    void findAnchorBm25_hitsByToken_andScope() {
        // mGlobal tokens="家庭 配偶 妻子"，mP1="工作 公司"，query token "家庭" → 命中 mGlobal
        List<UserMemory> globalHits = mapper.findAnchorBm25(U1, "家庭", 10, true, List.of());
        assertIds(globalHits, "BM25 globalOnly + token 家庭 → mGlobal", mGlobal);

        // mP1 tokens="工作 公司"，query "工作" → projectOnly[P1] 命中 mP1
        List<UserMemory> projHits = mapper.findAnchorBm25(U1, "工作", 10, false, List.of(p1));
        assertIds(projHits, "BM25 projectOnly[P1] + token 工作 → mP1", mP1);
    }

    @Test
    void findAnchorBm25_multiTokenOr_match() {
        // per-token OR：query "家庭 公司" → mGlobal(家庭) + mP1(公司) 都命中（combined scope）
        List<UserMemory> hits = mapper.findAnchorBm25(U1, "家庭 公司", 10, true, List.of(p1));
        assertIds(hits, "BM25 combined + tokens 家庭 公司 → mGlobal + mP1", mGlobal, mP1);
    }

    @Test
    void findAnchorBm25_noTokenMatch_returnsEmpty() {
        // query "旅行" 不在任何 anchor_tokens → 0 命中
        assertTrue(mapper.findAnchorBm25(U1, "旅行", 10, true, List.of()).isEmpty(),
                "无 token 命中 → 空");
    }

    @Test
    void findAnchorBm25_allOff_returnsNothing() {
        assertTrue(mapper.findAnchorBm25(U1, "家庭", 10, false, List.of()).isEmpty(),
                "全关 → 0 行");
    }

    @Test
    void findAnchorBm25_nullAnchorTokensSkipped() {
        // mMulti anchor_tokens NULL → tsv 空 → 即便 scope 可见也不命中
        List<UserMemory> hits = mapper.findAnchorBm25(U1, "shared", 10, true, List.of());
        assertTrue(hits.stream().noneMatch(m -> m.getId() == mMulti),
                "mMulti 无 anchor_tokens → BM25 不命中（优雅降级，回填后生效）");
    }

    // ============================ helpers ============================

    /** 单位轴 halfvec（dim0=1 其余 0）→ 自身距离 0、轴 1 距离 √2（sim<0）。 */
    private String halfAxis(int axis) {
        float[] v = new float[HalfVecUtil.DIM];
        v[axis] = 1.0f;
        return HalfVecUtil.toHalfVec(v);
    }

    /** 插一条 clean 记忆带 anchor_embedding + anchor_tokens（+ 可选挂项目）。anchorHalfvec/anchorTokens 为 null 则不落 anchor。 */
    private long insertAnchor(long user, String key, boolean isGlobal, String anchorHalfvec, String anchorTokens, Long project) {
        jdbc.update("""
                INSERT INTO user_memories
                  (user_id, category, memory_key, memory_key_zh, memory_value, source, confidence,
                   block_label, embedding, anchor_embedding, anchor_tokens, conflict_id, entities, is_global, created_at, updated_at)
                VALUES (?,?,?,?,'v','INFERRED',0.9,'b1',NULL,?::halfvec,?,NULL,NULL,?,now(),now())
                """,
                user, "FACT", key, key + "_zh", anchorHalfvec, anchorTokens, isGlobal);
        long id = jdbc.queryForObject("SELECT id FROM user_memories WHERE user_id=? AND memory_key=?", Long.class, user, key);
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
        jdbc.update("DELETE FROM user_memory_projects WHERE memory_id IN (SELECT id FROM user_memories WHERE user_id IN (?,?))", U1, U2);
        jdbc.update("DELETE FROM user_memories WHERE user_id IN (?,?)", U1, U2);
        jdbc.update("DELETE FROM project_members WHERE user_id IN (?,?)", U1, U2);
        jdbc.update("DELETE FROM projects WHERE created_by IN (?,?)", U1, U2);
        jdbc.update("DELETE FROM users WHERE id IN (?,?)", U1, U2);
    }
}
