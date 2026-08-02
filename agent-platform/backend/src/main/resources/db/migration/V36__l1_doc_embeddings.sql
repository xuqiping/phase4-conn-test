-- V36: RAG 召回升级 Phase3 — L1 文档级向量通道（治"换说法召回不到"根因 ④）
-- 背景：L1 文档元数据（summary/outline/importantRules）原只注入 prompt，从不参与召回。
--   doc 级语义锚对措辞远比 chunk 稳 → 单独 embed 进新表，召回时跨通道融合（L0 向量 + L1 向量 + jieba-BM25）。
-- 策略：每文档 1 行 L1 向量（L1 文本 = summary+outline+importantRules 拼接后 embed，dim 2048 halfvec）
--   复用 PG FTS + HNSW，零新依赖。不接 cross-encoder（启发式 RRF 精排）。

CREATE TABLE knowledge_doc_embeddings_doubao (
    id              BIGSERIAL    PRIMARY KEY,
    document_id     BIGINT       NOT NULL UNIQUE REFERENCES knowledge_documents(id) ON DELETE CASCADE,
    tenant_id       BIGINT       NOT NULL DEFAULT 1,
    kb_id           BIGINT       NOT NULL,
    embedding_model VARCHAR(64)  NOT NULL DEFAULT 'doubao',
    embedding       halfvec(2048) NOT NULL,
    content_hash    VARCHAR(128) NOT NULL,   -- L1 文本 sha256（embed 时算）；召回时不复校（无 node 可比对，drift 靠重解析触发新 job 接管）
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_docemb_hnsw ON knowledge_doc_embeddings_doubao USING hnsw (embedding halfvec_cosine_ops);
CREATE INDEX idx_docemb_kb   ON knowledge_doc_embeddings_doubao(tenant_id, kb_id);

-- UPSERT_L1 job 为 doc 级（无 node）→ node_id 放宽可空 + 加 document_id 列
ALTER TABLE knowledge_index_jobs ALTER COLUMN node_id DROP NOT NULL;
ALTER TABLE knowledge_index_jobs ADD COLUMN IF NOT EXISTS document_id BIGINT;
CREATE INDEX IF NOT EXISTS idx_job_doc_type ON knowledge_index_jobs(document_id, job_type) WHERE document_id IS NOT NULL;
