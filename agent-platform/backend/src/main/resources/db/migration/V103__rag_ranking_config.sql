-- V103: 企业级精准知识库 RAG 可插拔重排配置。
-- V102 已被认证模块占用；本表用 kb_id=NULL 表示管理员默认，非 NULL 表示知识库覆盖。

CREATE TABLE rag_ranking_configs (
    id                      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id               BIGINT       NOT NULL DEFAULT 1,
    kb_id                   BIGINT REFERENCES knowledge_bases(id) ON DELETE CASCADE,
    ranking_mode            VARCHAR(16)  NOT NULL, -- LLM/RERANK/DISABLED
    model                   VARCHAR(200),          -- 明确模型名；DISABLED 可空
    candidate_limit         INTEGER      NOT NULL DEFAULT 30,
    final_limit             INTEGER      NOT NULL DEFAULT 10,
    batch_size              INTEGER      NOT NULL DEFAULT 10,
    timeout_ms              INTEGER      NOT NULL DEFAULT 4000,
    fallback_policy         VARCHAR(32)  NOT NULL DEFAULT 'FAIL_CLOSED',
    high_accuracy_enabled   BOOLEAN      NOT NULL DEFAULT FALSE,
    config_version          VARCHAR(64)  NOT NULL,
    status                  VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE', -- ACTIVE/ARCHIVED
    created_by              BIGINT,
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_by              BIGINT,
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    deleted                 INTEGER      NOT NULL DEFAULT 0,
    version                 INTEGER      NOT NULL DEFAULT 0,
    CONSTRAINT ck_rag_ranking_mode CHECK (ranking_mode IN ('LLM', 'RERANK', 'DISABLED')),
    CONSTRAINT ck_rag_ranking_limits CHECK (
        candidate_limit BETWEEN 1 AND 200
        AND final_limit BETWEEN 1 AND candidate_limit
        AND batch_size BETWEEN 1 AND 50
        AND timeout_ms BETWEEN 100 AND 120000
    ),
    CONSTRAINT ck_rag_ranking_model CHECK (
        ranking_mode = 'DISABLED' OR (model IS NOT NULL AND length(trim(model)) > 0)
    )
);

-- 每个 KB 只能有一条 ACTIVE 配置；管理员默认每租户只能有一条 ACTIVE 配置。
CREATE UNIQUE INDEX uk_rag_ranking_kb_active
    ON rag_ranking_configs(tenant_id, kb_id)
    WHERE deleted = 0 AND status = 'ACTIVE' AND kb_id IS NOT NULL;
CREATE UNIQUE INDEX uk_rag_ranking_default_active
    ON rag_ranking_configs(tenant_id)
    WHERE deleted = 0 AND status = 'ACTIVE' AND kb_id IS NULL;
CREATE INDEX idx_rag_ranking_config_time
    ON rag_ranking_configs(tenant_id, updated_at DESC) WHERE deleted = 0;

COMMENT ON TABLE rag_ranking_configs IS '知识库重排配置；KB 覆盖优先，其次管理员默认，无配置明确报错。';
COMMENT ON COLUMN rag_ranking_configs.model IS '明确选择的模型名；禁止运行时硬编码或取供应商列表第一项。';
COMMENT ON COLUMN rag_ranking_configs.fallback_policy IS 'FAIL_CLOSED/FALLBACK_RRF/FALLBACK_NO_ANSWER 等显式降级策略。';

-- 回滚：DROP TABLE IF EXISTS rag_ranking_configs;

