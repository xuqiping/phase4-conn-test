-- V173: WP5 多模态 Step2 — IMAGE 文档级图片向量通道（doc 双向量之「图片原生向量」）
-- 背景：图片文档此前只有识图文本描述的向量（走 knowledge_embeddings_doubao，node 级），
--   图片本体像素语义从不参与召回 → 文本 query 与图片内容对不上时召回不到。
-- 策略：每 IMAGE 文档 1 行图片向量（原件 bytes→Base64→多模态 embed，dim 2048 halfvec），
--   单独建表（V36 L1 通道同款先例：通道分表而非同表加 modality 列——embeddings 表为
--   per-model 分表 + node_id UNIQUE，加列需动全部分表与 upsert 冲突键，爆炸半径大）。
--   job 型 UPSERT_IMAGE（doc 级，document_id 锚定，node_id NULL——V36 已放宽）。
--   检索消费在 WP5 Step3；本版只写不读。

CREATE TABLE knowledge_image_embeddings_doubao (
    id              BIGSERIAL    PRIMARY KEY,
    document_id     BIGINT       NOT NULL UNIQUE REFERENCES knowledge_documents(id) ON DELETE CASCADE,
    tenant_id       BIGINT       NOT NULL DEFAULT 1,
    kb_id           BIGINT       NOT NULL,
    embedding_model VARCHAR(64)  NOT NULL DEFAULT 'doubao',
    embedding       halfvec(2048) NOT NULL,
    content_hash    VARCHAR(128) NOT NULL,   -- 原件字节 sha256（embed 时算）；重换图→重解析→新 job 接管覆盖
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_imgemb_hnsw ON knowledge_image_embeddings_doubao USING hnsw (embedding halfvec_cosine_ops);
CREATE INDEX idx_imgemb_kb   ON knowledge_image_embeddings_doubao(tenant_id, kb_id);
