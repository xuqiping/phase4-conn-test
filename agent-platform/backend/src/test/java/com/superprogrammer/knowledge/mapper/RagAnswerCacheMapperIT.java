package com.superprogrammer.knowledge.mapper;

import com.superprogrammer.knowledge.dto.CacheCandidateRow;
import com.superprogrammer.knowledge.AbstractIntegrationTest;
import com.superprogrammer.knowledge.util.HalfVecUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RagAnswerCacheMapper 真实 PG+pgvector 集成测。
 * 验：halfvec key_embedding insert + HNSW searchCandidates 距离排序 + per-user 强制隔离 + deleteDecayed。
 * 真实 SQL（halfvec `<=>` / HNSW），H2 跑不了——故 @Tag("integration") 走 it profile。
 */
class RagAnswerCacheMapperIT extends AbstractIntegrationTest {

    @Autowired private RagAnswerCacheMapper mapper;
    @Autowired private JdbcTemplate jdbc;

    /** 隔离认证模块并发开发导致的 Captcha 自动配置缺失，本 IT 只验证知识库 Mapper。 */
    @MockBean private com.anji.captcha.service.CaptchaService ajCaptchaService;

    /** 单位轴 halfvec（dim0=1 其余 0）→ 与自身距离 0、与其他轴距离 √2≈1.414。 */
    private String halfAxis(int axis) {
        float[] v = new float[HalfVecUtil.DIM];
        v[axis] = 1.0f;
        return HalfVecUtil.toHalfVec(v);
    }

    @BeforeEach
    @AfterEach
    void clean() {
        jdbc.execute("TRUNCATE rag_answer_cache");
    }

    @Test
    void searchCandidates_returnsPerUserOrderedByDistance() {
        // user 7L：2 行（axis0 query 近 axis0 行；axis1 行更远）
        insert(101L, 7L, halfAxis(0), "q0", futureDecay(), "ACTIVE");
        insert(102L, 7L, halfAxis(1), "q1", futureDecay(), "ACTIVE");
        // user 8L：1 行（cross-user，须被 per-user WHERE 滤掉）
        insert(201L, 8L, halfAxis(0), "q0-other", futureDecay(), "ACTIVE");

        List<CacheCandidateRow> hits = mapper.searchCandidates(
                7L, halfAxis(0), "embed-a", "rank-v1", "pipe-v1", "prompt-v1", "snap-v1", 5);

        assertEquals(2, hits.size(), "仅返 user 7L 的 2 行（per-user 强制）");
        assertEquals(101L, hits.get(0).getId(), "axis0 行距离最近（自身 0）");
        assertTrue(hits.get(0).getCosineDistance() < hits.get(1).getCosineDistance(),
                "按 cosine 距离升序");
        assertTrue(hits.get(0).getCosineDistance() < 0.01, "自身距离 ~0");
    }

    @Test
    void searchCandidates_crossUserNeverReturned() {
        insert(301L, 7L, halfAxis(0), "q", futureDecay(), "ACTIVE");
        insert(302L, 8L, halfAxis(0), "q", futureDecay(), "ACTIVE");

        List<CacheCandidateRow> user7 = mapper.searchCandidates(
                7L, halfAxis(0), "embed-a", "rank-v1", "pipe-v1", "prompt-v1", "snap-v1", 5);
        assertEquals(1, user7.size());
        assertEquals(301L, user7.get(0).getId(), "user 7L 不应命中 user 8L 的行");

        List<CacheCandidateRow> user8 = mapper.searchCandidates(
                8L, halfAxis(0), "embed-a", "rank-v1", "pipe-v1", "prompt-v1", "snap-v1", 5);
        assertEquals(1, user8.size());
        assertEquals(302L, user8.get(0).getId());
    }

    @Test
    void searchCandidates_filtersInactive() {
        insert(401L, 7L, halfAxis(0), "q", futureDecay(), "ACTIVE");
        insert(402L, 7L, halfAxis(0), "q", futureDecay(), "DISABLED");   // 非 ACTIVE

        List<CacheCandidateRow> hits = mapper.searchCandidates(
                7L, halfAxis(0), "embed-a", "rank-v1", "pipe-v1", "prompt-v1", "snap-v1", 5);
        assertEquals(1, hits.size());
        assertEquals(401L, hits.get(0).getId(), "仅 ACTIVE 行");
    }

    @Test
    void bumpUsage_incrementsCount() {
        insert(501L, 7L, halfAxis(0), "q", futureDecay(), "ACTIVE");
        mapper.bumpUsage(501L);
        mapper.bumpUsage(501L);
        Integer count = jdbc.queryForObject(
                "SELECT usage_count FROM rag_answer_cache WHERE id = 501", Integer.class);
        assertEquals(2, count);
    }

    @Test
    void deleteDecayed_removesOnlyExpiredActive() {
        insert(601L, 7L, halfAxis(0), "q", futureDecay(), "ACTIVE");          // 未过期 → 留
        insert(602L, 7L, halfAxis(1), "q", pastDecay(), "ACTIVE");            // 过期 → 删
        insert(603L, 7L, halfAxis(2), "q", pastDecay(), "DISABLED");          // 过期但非 ACTIVE → 留

        int deleted = mapper.deleteDecayed(100);

        assertEquals(1, deleted);
        Integer remaining = jdbc.queryForObject("SELECT COUNT(*) FROM rag_answer_cache", Integer.class);
        assertEquals(2, remaining);   // 601 + 603
    }

    @Test
    void countDecayed_countsExpiredActiveOnly() {
        insert(701L, 7L, halfAxis(0), "q", futureDecay(), "ACTIVE");
        insert(702L, 7L, halfAxis(1), "q", pastDecay(), "ACTIVE");
        insert(703L, 7L, halfAxis(2), "q", pastDecay(), "DISABLED");
        assertEquals(1L, mapper.countDecayed());
    }

    @Test
    void searchCandidates_isolatesEmbeddingRankingPipelinePromptAndKnowledgeSnapshot() {
        insert(801L, 7L, halfAxis(0), "q", futureDecay(), "ACTIVE");

        assertEquals(1, mapper.searchCandidates(
                7L, halfAxis(0), "embed-a", "rank-v1", "pipe-v1", "prompt-v1", "snap-v1", 5).size());
        assertTrue(mapper.searchCandidates(
                7L, halfAxis(0), "embed-b", "rank-v1", "pipe-v1", "prompt-v1", "snap-v1", 5).isEmpty());
        assertTrue(mapper.searchCandidates(
                7L, halfAxis(0), "embed-a", "rank-v2", "pipe-v1", "prompt-v1", "snap-v1", 5).isEmpty());
        assertTrue(mapper.searchCandidates(
                7L, halfAxis(0), "embed-a", "rank-v1", "pipe-v2", "prompt-v1", "snap-v1", 5).isEmpty());
        assertTrue(mapper.searchCandidates(
                7L, halfAxis(0), "embed-a", "rank-v1", "pipe-v1", "prompt-v2", "snap-v1", 5).isEmpty());
        assertTrue(mapper.searchCandidates(
                7L, halfAxis(0), "embed-a", "rank-v1", "pipe-v1", "prompt-v1", "snap-v2", 5).isEmpty());
    }

    // ============================ helpers ============================

    /** jdbc 显式 id 插入（避 mapper 自增 id 无法回填 + 重排坑）；halfvec 走 ?::halfvec。 */
    private void insert(Long id, Long userId, String half, String query, OffsetDateTime decay, String status) {
        jdbc.update("""
                INSERT INTO rag_answer_cache
                  (id, tenant_id, scope_user_id, kb_ids, query_canonical, key_embedding, key_embedding_model,
                   ranking_config_version, pipeline_version, prompt_version, knowledge_snapshot,
                   answer, provenance_node_ids, evidence_hashes, permission_signature, confidence,
                   usage_count, decay_at, status, created_at, updated_at)
                VALUES (?,?,?,?,?,?::halfvec,?,?,?,?,?,?,?,?,?,?,?,?,?,now(),now())
                """, id, 1L, userId, "[]", query, half, "embed-a",
                "rank-v1", "pipe-v1", "prompt-v1", "snap-v1",
                "{\"answer\":\"a\"}", "[]", "[]", "sig-" + id, 0.9f, 0, decay, status);
    }

    private OffsetDateTime futureDecay() {
        return OffsetDateTime.now().plus(7, ChronoUnit.DAYS);
    }

    private OffsetDateTime pastDecay() {
        return OffsetDateTime.now().minus(1, ChronoUnit.HOURS);
    }
}
