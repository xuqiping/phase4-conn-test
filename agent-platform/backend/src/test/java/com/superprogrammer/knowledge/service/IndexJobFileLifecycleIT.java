package com.superprogrammer.knowledge.service;

import com.superprogrammer.file.entity.StoredFileEntity;
import com.superprogrammer.file.service.FileStorageService;
import com.superprogrammer.file.service.StoredFile;
import com.superprogrammer.knowledge.AbstractIntegrationTest;
import com.superprogrammer.knowledge.service.internal.IndexJobTxService;
import com.superprogrammer.knowledge.util.HalfVecUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * D5 文件生命周期集成测（设计 §10.5）—— 文档 INDEXED 后清原件字节 + stored_files.status=CLEANED。
 *
 * <p>真 PG16 + 真磁盘文件。两段验证：
 * <ul>
 *   <li>{@code completeUpsert} 完成最后一 job 使文档转 INDEXED 时，返回该文档 fileRef（worker 据此在事务外清理）；</li>
 *   <li>{@code cleanOriginalFileAfterIndex}（worker 事务外 glue）→ {@link FileStorageService#cleanAfterIndex}：
 *       删磁盘字节（load 再取 → NOT_FOUND）+ stored_files 置 CLEANED（<strong>保留行</strong>，区别于 delete 硬删）。</li>
 * </ul>
 * 知识完整性靠 knowledge_nodes + 向量，不依赖原件（重嵌读 nodes）。
 */
class IndexJobFileLifecycleIT extends AbstractIntegrationTest {

    @Autowired private IndexJobTxService txService;
    @Autowired private IndexJobWorker worker;
    @Autowired private FileStorageService fileStorageService;
    @Autowired private JdbcTemplate jdbc;

    private static final long KB = 9201L;
    private static final long U1 = 9201L;
    private static final long NODE = 9211L;
    private static final long JOB = 9221L;
    private static final String CONTENT_HASH = "hash1";

    @BeforeEach
    void seed() {
        clean();
        jdbc.update("INSERT INTO users (id, username, password) OVERRIDING SYSTEM VALUE VALUES (?,?,?)", U1, "u9201", "x");
        jdbc.update("INSERT INTO knowledge_bases (id, tenant_id, name, embedding_model) VALUES (?,?,?,?)",
                KB, 1L, "d5-lifecycle-kb", "doubao");
    }

    @AfterEach
    void clean() {
        jdbc.update("TRUNCATE knowledge_embeddings_doubao, knowledge_index_jobs, knowledge_nodes, "
                + "knowledge_documents, stored_files, knowledge_bases RESTART IDENTITY CASCADE");
        jdbc.update("DELETE FROM users WHERE id=?", U1);
    }

    @Test
    void completeUpsert_onLastJob_returnsFileRefAndMarksIndexed() {
        long docId = jdbc.queryForObject("INSERT INTO knowledge_documents "
                        + "(kb_id, title, status, file_ref, created_by) VALUES (?,?,?,?,?) RETURNING id",
                Long.class, KB, "d5-doc", "EMBEDDING", "/api/files/fake.xlsx", U1);
        jdbc.update("INSERT INTO knowledge_nodes "
                        + "(id, tenant_id, kb_id, document_id, node_type, level, content, content_hash, status, deleted) "
                        + "VALUES (?,?,?,?,?, 'L0','c',?, 'ACTIVE', 0)",
                NODE, 1L, KB, docId, "SECTION", CONTENT_HASH);
        jdbc.update("INSERT INTO knowledge_index_jobs "
                        + "(id, node_id, kb_id, job_type, content_hash, idempotency_key, status) "
                        + "VALUES (?,?,?, 'UPSERT', ?, 'idem-d5', 'PENDING')",
                JOB, NODE, KB, CONTENT_HASH);

        String fileRef = txService.completeUpsert(JOB, NODE, docId, KB, "doubao", halfvec(), CONTENT_HASH);

        assertThat(fileRef).isEqualTo("/api/files/fake.xlsx");
        assertThat(status(docId)).isEqualTo("INDEXED");
    }

    @Test
    void cleanOriginalFileAfterIndex_deletesBytesAndMarksCleanedRowKept() {
        // 落盘真实文件（ACTIVE）
        StoredFile f = fileStorageService.store(
                new MockMultipartFile("file", "doc.xlsx",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        new byte[]{1, 2, 3, 4}),
                U1, StoredFileEntity.SOURCE_KB, KB, null);
        assertThat(fileStorageService.load(f.fileId(), U1, false).exists()).isTrue();

        // worker 事务外清理 glue（retain 默认 false → 清）
        worker.cleanOriginalFileAfterIndex(f.url());

        // 字节已删：load 再取 → NOT_FOUND（文件不存在）
        assertThatThrownBy(() -> fileStorageService.load(f.fileId(), U1, false))
                .hasMessageContaining("文件不存在");
        // 登记行保留 + status=CLEANED（区别于 delete 的硬删行）
        String st = jdbc.queryForObject("SELECT status FROM stored_files WHERE file_id=?", String.class, f.fileId());
        assertThat(st).isEqualTo(StoredFileEntity.STATUS_CLEANED);
        Long cnt = jdbc.queryForObject("SELECT count(*) FROM stored_files WHERE file_id=?", Long.class, f.fileId());
        assertThat(cnt).isEqualTo(1L);
    }

    // ============================ helpers ============================

    private String status(long docId) {
        return jdbc.queryForObject("SELECT status FROM knowledge_documents WHERE id=?", String.class, docId);
    }

    private String halfvec() {
        float[] v = new float[HalfVecUtil.DIM];
        v[0] = 1.0f;
        return HalfVecUtil.toHalfVec(v);
    }
}
