package com.superprogrammer.chat.service;

import com.superprogrammer.chat.dto.MemoryContextPreviewVO;
import com.superprogrammer.chat.service.internal.MemoryConflictJudge;
import com.superprogrammer.chat.service.internal.MemoryScope;
import com.superprogrammer.knowledge.AbstractIntegrationTest;
import com.superprogrammer.knowledge.service.QueryExpansionService;
import com.superprogrammer.knowledge.util.HalfVecUtil;
import com.superprogrammer.llm.LlmGateway;
import com.superprogrammer.system.service.SystemSettingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * V38 预览召回过程集成测（真实 PG16 + pgvector + Redis，@MockBean 引擎降确定）。
 * <p>验 {@link MemoryService#previewContext} 在 LLM_KEY 模式透出 candidates + selectedKeys + channels：
 * <ul>
 *   <li>粗筛候选含家庭记忆（anchor 向量 + BM25 双通道命中 → channel=both）；</li>
 *   <li>LLM 精排选中 key 含配偶；</li>
 *   <li>通道命中统计 vector/bm25 ≥1。</li>
 * </ul>
 * H2 跑不了 halfvec/HNSW/tsvector —— @Tag("integration") 走 it profile。
 */
class MemoryPreviewDebugIT extends AbstractIntegrationTest {

    @Autowired private MemoryService memoryService;
    @Autowired private SystemSettingService systemSettingService;
    @Autowired private JdbcTemplate jdbc;
    @MockBean private LlmGateway llmGateway;
    @MockBean private QueryExpansionService queryExpansion;
    @MockBean private MemoryConflictJudge judge;

    private static final long U1 = 882_001L;
    private long mFam;   // 家庭记忆：anchor 轴 0 + tokens 家庭/配偶/家人，is_global

    @BeforeEach
    void seed() {
        clean();
        jdbc.update("INSERT INTO users (id, username, password) OVERRIDING SYSTEM VALUE VALUES (?,?,?)", U1, "u882001", "x");
        mFam = insertAnchor(U1, "spouse", "配偶", "妻子小美", "family", halfAxis(0), "家庭 配偶 妻子 家人", true);

        // LLM_KEY 模式 + 精排（写 it 库 system_settings，真实 SystemSettingService 读回）
        systemSettingService.updateMemoryRetrievalMode("LLM_KEY");
        systemSettingService.updateLlmKeyRerank(true);
        systemSettingService.updateLlmKeyCoarseTopN(40);

        // 引擎 mock：query 扩展返回轴 0 halfvec（命中 mFam 自身 sim=1.0）；精排选 spouse/family
        when(queryExpansion.expand(any(), any()))
                .thenReturn(new QueryExpansionService.ExpandedQuery("带家人出去玩", List.of(halfAxis(0))));
        when(llmGateway.embed(any(), any())).thenReturn(halfAxisFloats(0));
        when(judge.selectRelevantKeysBlocks(any(), any(), any())).thenReturn(
                new MemoryConflictJudge.RelevantDims(Set.of("spouse"), Set.of("配偶"), Set.of("family")));
    }

    @AfterEach
    void tearDown() {
        systemSettingService.updateMemoryRetrievalMode("LLM_FULL_CONTEXT");   // 复位（避免污染其它 IT）
        clean();
    }

    @Test
    void previewContext_llmKey_exposesCandidatesSelectedKeysChannels() {
        MemoryContextPreviewVO vo = memoryService.previewContext(MemoryScope.globalOnly(U1), "带家人出去玩");

        assertEquals("LLM_KEY", vo.getMode());
        assertNotNull(vo.getCandidates(), "粗筛候选非空");
        MemoryContextPreviewVO.CandidateVO fam = vo.getCandidates().stream()
                .filter(c -> "spouse".equals(c.getMemoryKey())).findFirst().orElseThrow();
        assertEquals("both", fam.getChannel(), "家庭记忆被向量+BM25 双通道命中");
        assertEquals("family", fam.getBlockLabel());
        assertNotNull(vo.getSelectedKeys(), "精排选中 key 非空");
        assertTrue(vo.getSelectedKeys().contains("spouse"), "选中含配偶");
        assertNotNull(vo.getChannels());
        assertTrue(vo.getChannels().getVector() >= 1, "向量通道命中 ≥1");
        assertTrue(vo.getChannels().getBm25() >= 1, "BM25 通道命中 ≥1");
        assertNotNull(vo.getContext(), "注入上下文非空");
        assertTrue(vo.getContext().contains("妻子小美"), "注入含家庭记忆 value");
    }

    @Test
    void previewContext_scopeSwitch_changesCandidates() {
        // projectOnly[U1 无项目] → 看不到 is_global 的 mFam → 候选空
        MemoryContextPreviewVO vo = memoryService.previewContext(
                new MemoryScope(U1, false, List.of()), "带家人出去玩");
        assertEquals("LLM_KEY", vo.getMode());
        assertTrue(vo.getCandidates() == null || vo.getCandidates().isEmpty(), "projectOnly 无项目 → mFam 被 scope 滤");
        assertNull(vo.getContext(), "无候选 → 不注入");
    }

    // ============================ helpers ============================

    private String halfAxis(int axis) {
        return HalfVecUtil.toHalfVec(halfAxisFloats(axis));
    }

    private float[] halfAxisFloats(int axis) {
        float[] v = new float[HalfVecUtil.DIM];
        v[axis] = 1.0f;
        return v;
    }

    private long insertAnchor(long user, String key, String keyZh, String value, String block,
                              String anchorHalfvec, String anchorTokens, boolean isGlobal) {
        jdbc.update("""
                INSERT INTO user_memories
                  (user_id, category, memory_key, memory_key_zh, memory_value, source, confidence,
                   block_label, embedding, anchor_embedding, anchor_tokens, conflict_id, entities, is_global, created_at, updated_at)
                VALUES (?,?,?,?,?,?,'0.9'::numeric,?,NULL,?::halfvec,?,NULL,NULL,?,now(),now())
                """,
                user, "FACT", key, keyZh, value, "INFERRED", block, anchorHalfvec, anchorTokens, isGlobal);
        return jdbc.queryForObject("SELECT id FROM user_memories WHERE user_id=? AND memory_key=?", Long.class, user, key);
    }

    private void clean() {
        jdbc.update("DELETE FROM user_memory_projects WHERE memory_id IN (SELECT id FROM user_memories WHERE user_id=?)", U1);
        jdbc.update("DELETE FROM user_memories WHERE user_id=?", U1);
        jdbc.update("DELETE FROM project_members WHERE user_id=?", U1);
        jdbc.update("DELETE FROM projects WHERE created_by=?", U1);
        jdbc.update("DELETE FROM users WHERE id=?", U1);
    }
}
