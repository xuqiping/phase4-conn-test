package com.superprogrammer.knowledge.service.internal;

import com.superprogrammer.knowledge.AbstractIntegrationTest;
import com.superprogrammer.knowledge.entity.KnowledgeReconciliationReport;
import com.superprogrammer.knowledge.util.HalfVecUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ReconciliationTxService 真实 PG 集成测（验 Phase E 特性 + 4 新 KnowledgeIndexJobMapper 法）。
 * seed drift（node hash≠emb hash）/ orphan（node 软删）/ DEAD job → scanKb 计数 + purgeOrphanEmbeddings 删。
 * 真实 SQL（content_hash <> 比较 / LEFT JOIN 孤儿检测 / status 计数），H2 跑不了。
 */
class ReconciliationIT extends AbstractIntegrationTest {

    @Autowired private ReconciliationTxService txService;
    @Autowired private JdbcTemplate jdbc;

    private static final long KB = 9001L;
    private static final String HALF = HalfVecUtil.toHalfVec(unitAxis());

    @BeforeEach
    void seed() {
        clean();
        // KB
        jdbc.update("INSERT INTO knowledge_bases (id, tenant_id, name, embedding_model) VALUES (?,?,?,?)",
                KB, 1L, "recon-kb-it", "doubao-embedding-vision");
        // 节点：11 对齐 / 12 漂移 / 13 软删（孤儿来源）
        jdbc.update("INSERT INTO knowledge_nodes (id, tenant_id, kb_id, node_type, level, content, content_hash, status, deleted) " +
                "VALUES (?,?,?,'SECTION','L0','n11','h11','ACTIVE',0)", 11L, 1L, KB);
        jdbc.update("INSERT INTO knowledge_nodes (id, tenant_id, kb_id, node_type, level, content, content_hash, status, deleted) " +
                "VALUES (?,?,?,'SECTION','L0','n12','h12','ACTIVE',0)", 12L, 1L, KB);
        jdbc.update("INSERT INTO knowledge_nodes (id, tenant_id, kb_id, node_type, level, content, content_hash, status, deleted) " +
                "VALUES (?,?,?,'SECTION','L0','n13','h13','ACTIVE',1)", 13L, 1L, KB);   // deleted=1
        // 向量：node11 对齐(h11) / node12 漂移(STALE≠h12) / node13 孤儿(node 软删)
        jdbc.update("INSERT INTO knowledge_embeddings_doubao (node_id, tenant_id, kb_id, content_hash, embedding) VALUES (?,?,?,?,?::halfvec)",
                11L, 1L, KB, "h11", HALF);
        jdbc.update("INSERT INTO knowledge_embeddings_doubao (node_id, tenant_id, kb_id, content_hash, embedding) VALUES (?,?,?,?,?::halfvec)",
                12L, 1L, KB, "STALE", HALF);
        jdbc.update("INSERT INTO knowledge_embeddings_doubao (node_id, tenant_id, kb_id, content_hash, embedding) VALUES (?,?,?,?,?::halfvec)",
                13L, 1L, KB, "h13", HALF);
        // job：1 DEAD + 1 DONE
        jdbc.update("INSERT INTO knowledge_index_jobs (node_id, kb_id, job_type, content_hash, idempotency_key, status) " +
                "VALUES (?,?, 'UPSERT','h11','idem-dead','DEAD')", 11L, KB);
        jdbc.update("INSERT INTO knowledge_index_jobs (node_id, kb_id, job_type, content_hash, idempotency_key, status) " +
                "VALUES (?,?, 'UPSERT','h11','idem-done','DONE')", 11L, KB);
    }

    @AfterEach
    void clean() {
        jdbc.update("TRUNCATE knowledge_reconciliation_reports, knowledge_index_jobs, knowledge_embeddings_doubao, knowledge_nodes, knowledge_documents, knowledge_bases RESTART IDENTITY CASCADE");
    }

    @Test
    void scanKb_countsDriftOrphanDeadAndInsertsReport() {
        KnowledgeReconciliationReport r = txService.scanKb(KB);

        assertEquals(2, r.getTotalNodes(), "ACTIVE 非软删节点 = 11,12（13 软删不计）");
        assertEquals(1, r.getDriftCount(), "node12 content_hash(h12) ≠ emb(STALE) → drift");
        assertEquals(1, r.getOrphanCount(), "node13 软删 → 其 emb 孤儿");
        assertEquals(1, r.getDeadJobCount(), "DEAD job 1 个（stuck 0）");
        assertEquals(0, r.getRepairedCount());
        assertNotNull(r.getId(), "报告已插库");
    }

    @Test
    void purgeOrphanEmbeddings_removesOrphanAndRescanDropsToZero() {
        assertEquals(1, txService.scanKb(KB).getOrphanCount());

        int deleted = txService.purgeOrphanEmbeddings(KB);
        assertEquals(1, deleted);

        KnowledgeReconciliationReport r2 = txService.scanKb(KB);
        assertEquals(0, r2.getOrphanCount(), "孤儿清后 rescan=0");
    }

    @Test
    void purgeDecayedAnswerCache_realDb_noOpWhenEmpty() {
        // 本库无 rag_answer_cache decay 行（其他 IT 自清），返 0 不抛
        assertEquals(0, txService.purgeDecayedAnswerCache(100, 10));
    }

    private static float[] unitAxis() {
        float[] v = new float[HalfVecUtil.DIM];
        v[0] = 1.0f;
        return v;
    }
}
