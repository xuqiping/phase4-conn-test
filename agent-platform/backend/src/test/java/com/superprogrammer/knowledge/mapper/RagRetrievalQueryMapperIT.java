package com.superprogrammer.knowledge.mapper;

import com.superprogrammer.knowledge.AbstractIntegrationTest;
import com.superprogrammer.knowledge.dto.RagQueryRow;
import com.superprogrammer.knowledge.util.JiebaTokenizer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RagRetrievalQueryMapper.bm25HitsJieba 真实 PG 集成测（Phase2 V35）。
 * 验：jieba 分词后 content_tokens_tsv @@ plainto_tsquery('simple', ...) 命中换说法的中文 L2 chunk。
 * 'simple' tsvector + H2 跑不了 → @Tag("integration") it profile。
 *
 * <p>注意：中文常量经 JdbcTemplate 参数绑定（PreparedStatement setString）传递，不出现在 SQL 字面量里
 * （Maven 默认 GBK 源码编码会令中文字面量 mojibake）。所有中文走 `?` 占位。
 */
class RagRetrievalQueryMapperIT extends AbstractIntegrationTest {

    @Autowired private RagRetrievalQueryMapper mapper;
    @Autowired private JdbcTemplate jdbc;

    private Long kbId;
    private Long docId;

    @BeforeEach
    void seed() {
        clean();
        String kbName = "bm25-jieba-it-" + System.nanoTime();
        jdbc.update("INSERT INTO knowledge_bases(tenant_id, name, embedding_model, status) "
                + "VALUES (1, ?, 'doubao', 'ACTIVE')", kbName);
        kbId = jdbc.queryForObject("SELECT id FROM knowledge_bases WHERE name=?", Long.class, kbName);

        jdbc.update("INSERT INTO knowledge_documents(kb_id, title, status) VALUES (?, 'install-deploy-manual', 'INDEXED')",
                kbId);
        docId = jdbc.queryForObject(
                "SELECT id FROM knowledge_documents WHERE kb_id=? ORDER BY id DESC LIMIT 1", Long.class, kbId);
    }

    @AfterEach
    void clean() {
        jdbc.execute("TRUNCATE knowledge_bases CASCADE");
    }

    private void insertL2(long nodeId, String title, String contentTokens) {
        jdbc.update("INSERT INTO knowledge_nodes"
                + "(id, kb_id, document_id, parent_id, node_type, level, title, content, content_tokens,"
                + " content_hash, status) "
                + "VALUES (?,?,?,?,'SECTION','L2',?,?,?,?,'ACTIVE')",
                nodeId, kbId, docId, nodeId, title, contentTokens, contentTokens, "hash" + nodeId);
    }

    /** 构造 2048 维 halfvec 文本：hotIndex 位为 1，余 0（用于 cosine 序测，dim 与 doubao/V36 一致）。 */
    private static String halfvec(int hotIndex) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 2048; i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append(i == hotIndex ? 1 : 0);
        }
        return sb.append("]").toString();
    }

    @Test
    void bm25HitsJieba_matchesParaphrasedChineseQuery() {
        // L2 chunk content_tokens 含「安装/部署」（中文经参数绑定）
        insertL2(1001L, "setup-deploy", "本 节 描述 系统 的 安装 与 部署 步骤");
        // 干扰节点（无关词）
        insertL2(1002L, "login-logout", "用户 登录 与 注销 流程");

        // query 换说法：「如何安装部署」→ jieba 分词后喂 simple tsquery
        String q = JiebaTokenizer.tokenize("如何安装部署");
        List<RagQueryRow.L2Row> hits = mapper.bm25HitsJieba(kbId, q, List.of(docId));

        assertFalse(hits.isEmpty(), "应命中含「安装/部署」的 chunk");
        assertEquals(1001L, hits.get(0).getNodeId(), "命中的是安装部署 chunk，非登录注销");
        assertNotNull(hits.get(0).getBm25Rank(), "bm25_rank 非空");
    }

    @Test
    void bm25HitsJieba_noTokenMatch_returnsEmpty() {
        insertL2(2001L, "login-logout", "用户 登录 与 注销 流程");
        String q = JiebaTokenizer.tokenize("安装部署");
        List<RagQueryRow.L2Row> hits = mapper.bm25HitsJieba(kbId, q, List.of(docId));
        assertTrue(hits.isEmpty(), "无关词 chunk 不命中");
    }

    @Test
    void bm25HitsJieba_nullContentTokens_degradesGracefully() {
        // content_tokens IS NULL（未回填）→ content_tokens_tsv 空 → 不命中，不报错
        jdbc.update("INSERT INTO knowledge_nodes"
                + "(id, kb_id, document_id, node_type, level, title, content, content_hash, status) "
                + "VALUES (3001, ?, ?, 'SECTION','L2', 'old-node', 'raw install content', 'hash3001', 'ACTIVE')",
                kbId, docId);
        String q = JiebaTokenizer.tokenize("安装部署");
        List<RagQueryRow.L2Row> hits = mapper.bm25HitsJieba(kbId, q, List.of(docId));
        assertTrue(hits.isEmpty(), "未回填节点 content_tokens NULL 应优雅降级空命中");
    }

    // ============================ Phase3 denseRecallL1 / fetchL2ChildrenByDoc ============================

    @Test
    void denseRecallL1_ranksByCosineDistance() {
        // doc A 的 L1 向量 = dim0 方向（与 query 同向）；doc B = dim1 方向（正交）。query=dim0 → A 更近排首
        jdbc.update("INSERT INTO knowledge_doc_embeddings_doubao"
                + "(document_id, tenant_id, kb_id, embedding_model, embedding, content_hash) "
                + "VALUES (?,1,?,'doubao',?::halfvec,'l1hashA')", docId, kbId, halfvec(0));
        jdbc.update("INSERT INTO knowledge_documents(kb_id, title, status) VALUES (?, 'docB-L1', 'INDEXED')", kbId);
        Long docB = jdbc.queryForObject("SELECT id FROM knowledge_documents WHERE title='docB-L1'", Long.class);
        jdbc.update("INSERT INTO knowledge_doc_embeddings_doubao"
                + "(document_id, tenant_id, kb_id, embedding_model, embedding, content_hash) "
                + "VALUES (?,1,?,'doubao',?::halfvec,'l1hashB')", docB, kbId, halfvec(1));

        List<RagQueryRow.L1RecallRow> rows = mapper.denseRecallL1(kbId, halfvec(0), true, List.of(), null, 10);

        assertEquals(2, rows.size(), "L1 向量表两 doc 均召回");
        assertEquals(docId, rows.get(0).getDocumentId(), "与 query 同向的 doc A 余弦距离最小，排首位");
        assertNotNull(rows.get(0).getCosineDistance());
    }

    @Test
    void fetchL2ChildrenByDoc_returnsAllActiveL2OfDocs() {
        // L1 命中但 L0 父未进 topM 的文档：按 doc 维度取其全部 L2 子节点（不限 parent∈topM）
        insertL2(5001L, "docA-chunk1", "安装 步骤");
        insertL2(5002L, "docA-chunk2", "部署 步骤");

        List<RagQueryRow.L2Row> rows = mapper.fetchL2ChildrenByDoc(kbId, List.of(docId));

        assertEquals(2, rows.size(), "返回该 doc 全部 ACTIVE L2 子节点");
    }
}
