-- =====================================================================
-- V17: 企业级 RAG 知识库（设计 §8，v4 断根后）
-- 配套：项目工程文档/设计/后续其他功能设计/
--   企业级RAG向量库知识库设计v3.md（v4 一致态）
--   RAG-v4-预算账与不变式.md（权威）
--
-- Phase1 假设：
--   - 单租户（tenant_id 默认 1）
--   - active embedding 模型 = doubao（dim 2048，按实际模型调整）
--   - 向量表：embeddings/backlog 按模型分表（_doubao）；facts/answer_cache 单表
--   - episode 无向量列（D8）
--   - L2 ≤1024（D2），context_hash 占位（Phase1 不上 Contextual Retrieval）
--   - 向量列用 halfvec(2048) 而非 vector(2048)：pgvector HNSW 索引硬限 ≤2000 维，
--     Doubao embedding=2048 >2000 → 必须用 halfvec（HNSW ≤4000 维）。ops 用
--     halfvec_cosine_ops。实测 PG16/pgvector0.8.2 通过（2026-06-18）。
--
-- ⚠️ Phase1 第 0 步 blocker：pgvector 扩展必须先可加载（Windows 部署 vector.dll）。
--    CREATE EXTENSION 失败则本迁移阻塞（§15.1）。
-- =====================================================================

-- 0. pgvector 扩展
CREATE EXTENSION IF NOT EXISTS vector;

-- ---------------------------------------------------------------------
-- §8.1  knowledge_bases
-- ---------------------------------------------------------------------
CREATE TABLE knowledge_bases (
    id                      BIGSERIAL PRIMARY KEY,
    tenant_id               BIGINT      NOT NULL DEFAULT 1,
    name                    VARCHAR(200) NOT NULL,
    description             TEXT,
    visibility              VARCHAR(32) NOT NULL DEFAULT 'PRIVATE',   -- PRIVATE/TEAM/PUBLIC
    embedding_model         VARCHAR(64) NOT NULL DEFAULT 'doubao',    -- D3 标量 active 模型
    rerank_model            VARCHAR(64),
    chunk_strategy          VARCHAR(64),
    status                  VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    effective_partition     VARCHAR(128),
    created_by              BIGINT,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_by              BIGINT,
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted                 INTEGER     NOT NULL DEFAULT 0,
    version                 INTEGER     NOT NULL DEFAULT 0,
    CONSTRAINT uk_kb_tenant_name UNIQUE (tenant_id, name)
);
CREATE INDEX idx_kb_tenant ON knowledge_bases(tenant_id) WHERE deleted = 0;

-- ---------------------------------------------------------------------
-- §8.1a knowledge_documents（L1 元数据锚点）
-- ---------------------------------------------------------------------
CREATE TABLE knowledge_documents (
    id                BIGSERIAL PRIMARY KEY,
    kb_id             BIGINT      NOT NULL REFERENCES knowledge_bases(id) ON DELETE CASCADE,
    directory_id      BIGINT,
    title             VARCHAR(500) NOT NULL,
    doc_type          VARCHAR(64),                              -- policy/manual/faq/api/...
    status            VARCHAR(32) NOT NULL DEFAULT 'PENDING',   -- PENDING/PARSING/SUMMARIZING/EMBEDDING/INDEXED/FAILED
    current_version_id BIGINT,
    l1_metadata       TEXT,                                     -- JSON: summary/outline/usageScenarios/importantRules
    file_ref          TEXT,
    file_hash         VARCHAR(128),
    effective_at      TIMESTAMPTZ,
    deadline          TIMESTAMPTZ,
    created_by        BIGINT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_by        BIGINT,
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted           INTEGER     NOT NULL DEFAULT 0,
    version           INTEGER     NOT NULL DEFAULT 0
);
CREATE INDEX idx_doc_kb ON knowledge_documents(kb_id) WHERE deleted = 0;
CREATE INDEX idx_doc_file_hash ON knowledge_documents(file_hash) WHERE deleted = 0;

-- ---------------------------------------------------------------------
-- §8.1b knowledge_document_versions
-- ---------------------------------------------------------------------
CREATE TABLE knowledge_document_versions (
    id                BIGSERIAL PRIMARY KEY,
    document_id       BIGINT NOT NULL REFERENCES knowledge_documents(id) ON DELETE CASCADE,
    version_no        INTEGER NOT NULL,
    parent_version_id BIGINT,
    content_hash      VARCHAR(128),
    change_note       TEXT,
    effective_at      TIMESTAMPTZ,
    status            VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',    -- ACTIVE/STALE/ARCHIVED
    created_by        BIGINT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_docver_doc_no UNIQUE (document_id, version_no)
);
CREATE INDEX idx_docver_doc ON knowledge_document_versions(document_id);

-- ---------------------------------------------------------------------
-- §8.2  knowledge_nodes（L0 摘要 / L2 原文）
--   content_tsv：generated column，统一 BM25（Phase1 'simple' 配置；中文分词 Phase2 升 zhparser/jieba）
-- ---------------------------------------------------------------------
CREATE TABLE knowledge_nodes (
    id            BIGSERIAL PRIMARY KEY,
    tenant_id     BIGINT      NOT NULL DEFAULT 1,
    kb_id         BIGINT      NOT NULL REFERENCES knowledge_bases(id) ON DELETE CASCADE,
    document_id   BIGINT      REFERENCES knowledge_documents(id) ON DELETE CASCADE,
    parent_id     BIGINT,
    path          TEXT,
    node_type     VARCHAR(32) NOT NULL,                          -- DIRECTORY/SECTION/TABLE/FAQ
    level         VARCHAR(8)  NOT NULL,                          -- L0/L2（L1 移至 documents）
    title         VARCHAR(500),
    content       TEXT,                                          -- L0 摘要 / L2 原文（唯一真相源）
    content_tsv   tsvector GENERATED ALWAYS AS (to_tsvector('simple', coalesce(content, ''))) STORED,
    metadata      TEXT,                                          -- JSON
    token_count   INTEGER,
    content_hash  VARCHAR(128) NOT NULL,
    context_hash  VARCHAR(128) NOT NULL DEFAULT '__phase1_placeholder__',  -- Phase1 占位，Phase2 真实计算（§4.3.1）
    status        VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',         -- ACTIVE/STALE/ARCHIVED
    version_id    BIGINT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted       INTEGER     NOT NULL DEFAULT 0,
    version       INTEGER     NOT NULL DEFAULT 0
);
CREATE INDEX idx_node_tsv       ON knowledge_nodes USING GIN (content_tsv);
CREATE INDEX idx_node_kb_level  ON knowledge_nodes(tenant_id, kb_id, level) WHERE deleted = 0;
CREATE INDEX idx_node_doc       ON knowledge_nodes(document_id) WHERE deleted = 0;
CREATE INDEX idx_node_parent    ON knowledge_nodes(parent_id) WHERE deleted = 0;

-- ---------------------------------------------------------------------
-- §8.3  knowledge_embeddings_doubao（L0 dense 向量，按 active 模型分表）
--   L2 不向量化（D1）。dim=2048 按 doubao 实际模型调整。
--   HNSW + (tenant,kb) 范围（§15.1：Phase1 partial index / 分区，KB≤200 阈值）
-- ---------------------------------------------------------------------
CREATE TABLE knowledge_embeddings_doubao (
    id                BIGSERIAL PRIMARY KEY,
    node_id           BIGINT      NOT NULL UNIQUE REFERENCES knowledge_nodes(id) ON DELETE CASCADE,
    tenant_id         BIGINT      NOT NULL DEFAULT 1,
    kb_id             BIGINT      NOT NULL,
    node_level        VARCHAR(8)  NOT NULL DEFAULT 'L0',         -- 固定 L0（L2 不向量化，D1）
    embedding_model   VARCHAR(64) NOT NULL DEFAULT 'doubao',     -- 表名已含，冗余便于查询
    embedding         halfvec(2048) NOT NULL,
    external_vector_id TEXT,
    metadata          TEXT,
    content_hash      VARCHAR(128) NOT NULL,                     -- 不变式 I1：= node.content_hash
    context_hash      VARCHAR(128) NOT NULL DEFAULT '__phase1_placeholder__',
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
-- HNSW 向量索引（cosine）。权限/范围过滤为 post-ANN（§7.4/§15.1）。
CREATE INDEX idx_emb_doubao_hnsw ON knowledge_embeddings_doubao
    USING hnsw (embedding halfvec_cosine_ops);
CREATE INDEX idx_emb_doubao_kb   ON knowledge_embeddings_doubao(tenant_id, kb_id, node_level);

-- ---------------------------------------------------------------------
-- §8.4  knowledge_permissions（subject_type 含 SERVICE_ACCOUNT）
-- ---------------------------------------------------------------------
CREATE TABLE knowledge_permissions (
    id            BIGSERIAL PRIMARY KEY,
    tenant_id     BIGINT      NOT NULL DEFAULT 1,
    target_type   VARCHAR(16) NOT NULL,        -- KB/DIRECTORY/DOCUMENT
    target_id     BIGINT      NOT NULL,
    subject_type  VARCHAR(16) NOT NULL,        -- USER/ROLE/DEPARTMENT/SERVICE_ACCOUNT
    subject_id    BIGINT      NOT NULL,
    can_read      BOOLEAN     NOT NULL DEFAULT FALSE,
    can_write     BOOLEAN     NOT NULL DEFAULT FALSE,
    can_manage    BOOLEAN     NOT NULL DEFAULT FALSE,
    granted_by    BIGINT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_perm UNIQUE (tenant_id, target_type, target_id, subject_type, subject_id)
);
CREATE INDEX idx_perm_target ON knowledge_permissions(target_type, target_id);
CREATE INDEX idx_perm_subject ON knowledge_permissions(subject_type, subject_id);

-- ---------------------------------------------------------------------
-- §8.6  knowledge_index_jobs（Outbox：原文↔向量最终一致，§7.3.2）
-- ---------------------------------------------------------------------
CREATE TABLE knowledge_index_jobs (
    id               BIGSERIAL PRIMARY KEY,
    node_id          BIGINT      NOT NULL,
    kb_id            BIGINT      NOT NULL,
    job_type         VARCHAR(16) NOT NULL,    -- UPSERT/DELETE/REINDEX
    content_hash     VARCHAR(128) NOT NULL,
    context_hash     VARCHAR(128) NOT NULL DEFAULT '__phase1_placeholder__',
    status           VARCHAR(16) NOT NULL DEFAULT 'PENDING',  -- PENDING/RUNNING/DONE/FAILED/DEAD
    attempt          INTEGER     NOT NULL DEFAULT 0,
    max_attempt      INTEGER     NOT NULL DEFAULT 5,
    locked_until     TIMESTAMPTZ,
    idempotency_key  VARCHAR(160) NOT NULL,
    visibility_event BOOLEAN     NOT NULL DEFAULT FALSE,
    last_error       TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_job_idem UNIQUE (idempotency_key)
);
CREATE INDEX idx_job_status_lock ON knowledge_index_jobs(status, locked_until);
CREATE INDEX idx_job_node_type   ON knowledge_index_jobs(node_id, job_type);

-- ---------------------------------------------------------------------
-- §8.7  knowledge_reconciliation_reports（周期对账，§7.3.6）
-- ---------------------------------------------------------------------
CREATE TABLE knowledge_reconciliation_reports (
    id                   BIGSERIAL PRIMARY KEY,
    kb_id                BIGINT      NOT NULL,
    scanned_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    total_nodes          INTEGER     NOT NULL DEFAULT 0,
    drift_count          INTEGER     NOT NULL DEFAULT 0,    -- content/context hash 不一致
    orphan_count         INTEGER     NOT NULL DEFAULT 0,
    stale_with_embedding INTEGER     NOT NULL DEFAULT 0,
    repaired_count       INTEGER     NOT NULL DEFAULT 0,
    dead_job_count       INTEGER     NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_recon_kb ON knowledge_reconciliation_reports(kb_id, scanned_at);

-- ---------------------------------------------------------------------
-- §8.5  rag_retrieval_logs（可观测审计流，全量只追加）
-- ---------------------------------------------------------------------
CREATE TABLE rag_retrieval_logs (
    id                BIGSERIAL PRIMARY KEY,
    trace_id          VARCHAR(64)  NOT NULL,
    tenant_id         BIGINT       NOT NULL DEFAULT 1,
    user_id           BIGINT,
    identity_type     VARCHAR(16)  NOT NULL DEFAULT 'USER',   -- USER/SERVICE_ACCOUNT
    kb_ids            TEXT,
    query             TEXT,
    rewritten_query   TEXT,
    mode              VARCHAR(16),                            -- ECONOMY/BALANCED/PRECISION
    candidates_l0     TEXT,
    l2_lexical_fallback BOOLEAN  NOT NULL DEFAULT FALSE,      -- D1：BM25 vote 改排序
    evidence_l2       TEXT,
    memory_hits       TEXT,
    crag_verdict      VARCHAR(16),
    token_budget      TEXT,
    latency_ms        BIGINT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_raglog_trace ON rag_retrieval_logs(trace_id);
CREATE INDEX idx_raglog_tenant_time ON rag_retrieval_logs(tenant_id, created_at);

-- ---------------------------------------------------------------------
-- §8.8  rag_memory_episodes（M1 情景记忆；无向量列、不分表，D8）
-- ---------------------------------------------------------------------
CREATE TABLE rag_memory_episodes (
    id                 BIGSERIAL PRIMARY KEY,
    tenant_id          BIGINT       NOT NULL DEFAULT 1,
    user_id            BIGINT,
    scope_user_id      BIGINT,                              -- 强制 per-user
    kb_ids             TEXT,
    trace_id           VARCHAR(64),
    query_raw          TEXT,
    query_canonical    TEXT,                                -- consolidation 离线按需 re-embed
    answer             TEXT,
    evidence_node_ids  TEXT,
    evidence_hashes    TEXT,
    citations          TEXT,
    feedback           VARCHAR(16),                         -- useful/useless/irrelevant/missing
    confidence         REAL,
    mode               VARCHAR(16),
    abstained          BOOLEAN      NOT NULL DEFAULT FALSE,
    consolidation_role VARCHAR(16),                         -- CACHE_SOURCE/CONSOLIDATION_SOURCE/BOTH
    status             VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE/REVOKED
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_ep_scope_time ON rag_memory_episodes(tenant_id, scope_user_id, created_at);
CREATE INDEX idx_ep_role_abstain ON rag_memory_episodes(consolidation_role, abstained);

-- ---------------------------------------------------------------------
-- §8.9  rag_memory_facts（M2 语义软提示；key_embedding 绑 active 模型，单表，§7.3.11 step5）
-- ---------------------------------------------------------------------
CREATE TABLE rag_memory_facts (
    id                  BIGSERIAL PRIMARY KEY,
    tenant_id           BIGINT      NOT NULL DEFAULT 1,
    kb_ids              TEXT,
    fact_type           VARCHAR(24) NOT NULL,    -- synonym/rewrite_template/preference/domain_hint（无 cached_answer，D7）
    key                 TEXT,
    key_embedding       halfvec(2048),
    key_embedding_model VARCHAR(64) NOT NULL DEFAULT 'doubao',
    value               TEXT,
    provenance_node_ids TEXT,
    provenance_episode_ids TEXT,
    confidence          REAL        NOT NULL DEFAULT 0.5,
    usage_count         INTEGER     NOT NULL DEFAULT 0,
    decay_at            TIMESTAMPTZ,
    scope_user_id       BIGINT,                 -- 空=租户级
    status              VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE/DISABLED/ARCHIVED
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_facts_hnsw ON rag_memory_facts USING hnsw (key_embedding halfvec_cosine_ops);
CREATE INDEX idx_facts_type ON rag_memory_facts(tenant_id, scope_user_id, fact_type, status);
CREATE INDEX idx_facts_decay ON rag_memory_facts(tenant_id, status, decay_at);

-- ---------------------------------------------------------------------
-- §8.9a rag_answer_cache（D7 拆出；跨会话语义缓存，per-user + 校验链）
-- ---------------------------------------------------------------------
CREATE TABLE rag_answer_cache (
    id                  BIGSERIAL PRIMARY KEY,
    tenant_id           BIGINT      NOT NULL DEFAULT 1,
    scope_user_id       BIGINT      NOT NULL,                -- 强制 per-user，非空
    kb_ids              TEXT,
    query_canonical     TEXT,
    key_embedding       halfvec(2048) NOT NULL,
    key_embedding_model VARCHAR(64) NOT NULL DEFAULT 'doubao',
    answer              TEXT,                               -- 答案 JSON（脱敏）
    provenance_node_ids TEXT,
    evidence_hashes     TEXT,                               -- 命中逐条 content_hash 二次校验
    permission_signature VARCHAR(128) NOT NULL,
    doc_version_set     TEXT,
    confidence          REAL        NOT NULL DEFAULT 0.5,
    usage_count         INTEGER     NOT NULL DEFAULT 0,
    decay_at            TIMESTAMPTZ,
    status              VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE/DISABLED/ARCHIVED/REVOKED
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_cache_hnsw ON rag_answer_cache USING hnsw (key_embedding halfvec_cosine_ops);
CREATE INDEX idx_cache_scope ON rag_answer_cache(tenant_id, scope_user_id, status);
CREATE INDEX idx_cache_decay ON rag_answer_cache(scope_user_id, decay_at);

-- ---------------------------------------------------------------------
-- §8.10 rag_ingestion_backlog_doubao（M3 active-learning 缺口；per-model 分表）
-- ---------------------------------------------------------------------
CREATE TABLE rag_ingestion_backlog_doubao (
    id                  BIGSERIAL PRIMARY KEY,
    tenant_id           BIGINT      NOT NULL DEFAULT 1,
    kb_ids              TEXT,
    query_canonical     TEXT,
    query_embedding     halfvec(2048),
    query_embedding_model VARCHAR(64) NOT NULL DEFAULT 'doubao',
    gap_reason          VARCHAR(32),    -- abstention/low_confidence/user_missing/irrelevant_citation
    occurrences         INTEGER     NOT NULL DEFAULT 1,
    suggested_sources   TEXT,
    priority            REAL        NOT NULL DEFAULT 0,
    status              VARCHAR(16) NOT NULL DEFAULT 'OPEN',  -- OPEN/INGESTING/RESOLVED/IGNORED
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_backlog_hnsw ON rag_ingestion_backlog_doubao USING hnsw (query_embedding halfvec_cosine_ops);
CREATE INDEX idx_backlog_status ON rag_ingestion_backlog_doubao(tenant_id, status, priority);

-- ---------------------------------------------------------------------
-- §8.11 embedding_model_versions（模型版本注册表）
-- ---------------------------------------------------------------------
CREATE TABLE embedding_model_versions (
    id            BIGSERIAL PRIMARY KEY,
    model_code    VARCHAR(64) NOT NULL,                 -- doubao-embedding / bge-m3 / ...
    dim           INTEGER     NOT NULL,
    table_name    VARCHAR(128) NOT NULL,                -- knowledge_embeddings_{model}
    memory_table_backlog VARCHAR(128),                  -- rag_ingestion_backlog_{model}
    status        VARCHAR(16) NOT NULL DEFAULT 'CANDIDATE',  -- CANDIDATE/SHADOW/ACTIVE/RETIRED
    provider      VARCHAR(64),
    notes         TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_emb_model UNIQUE (model_code)
);

-- Phase1 种子：doubao 为 active 模型
INSERT INTO embedding_model_versions (model_code, dim, table_name, memory_table_backlog, status, provider, notes)
VALUES ('doubao', 2048, 'knowledge_embeddings_doubao', 'rag_ingestion_backlog_doubao', 'ACTIVE', 'doubao',
        'Phase1 active 模型；dim 按实际 doubao embedding 模型调整');
