-- V101: 企业级精准知识库 RAG 的 Pipeline 版本与全链路 Trace 基座。
-- PostgreSQL 是权威数据源；本迁移只新增表，不修改既有 rag_retrieval_logs，便于双轨迁移和回滚。

-- 一行代表一套不可变的检索流水线配置快照。
CREATE TABLE rag_pipeline_versions (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id           BIGINT       NOT NULL DEFAULT 1,
    version_code        VARCHAR(64)  NOT NULL,
    status              VARCHAR(16)  NOT NULL DEFAULT 'DRAFT', -- DRAFT/ACTIVE/ARCHIVED
    config_snapshot     JSONB        NOT NULL DEFAULT '{}'::jsonb,
    config_hash         VARCHAR(128) NOT NULL,
    created_by          BIGINT,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_rag_pipeline_tenant_version UNIQUE (tenant_id, version_code)
);
CREATE INDEX idx_rag_pipeline_status ON rag_pipeline_versions(tenant_id, status, created_at DESC);

-- 一行代表一次用户检索的总运行记录，是召回、重排、模型调用和后台日志的总入口。
CREATE TABLE rag_retrieval_runs (
    id                    UUID PRIMARY KEY,
    trace_id              VARCHAR(64)  NOT NULL,
    tenant_id             BIGINT       NOT NULL DEFAULT 1,
    user_id               BIGINT,
    kb_ids                JSONB        NOT NULL DEFAULT '[]'::jsonb,
    query_hash            VARCHAR(128) NOT NULL,
    query_type            VARCHAR(32),
    pipeline_version_id   BIGINT REFERENCES rag_pipeline_versions(id),
    knowledge_snapshot    VARCHAR(128),
    status                VARCHAR(32)  NOT NULL DEFAULT 'RUNNING', -- RUNNING/SUCCEEDED/FAILED/ABSTAINED
    result_state          VARCHAR(32),
    latency_ms            BIGINT,
    error_code            VARCHAR(64),
    error_summary         VARCHAR(1000),
    started_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    finished_at           TIMESTAMPTZ
);
CREATE INDEX idx_rag_retrieval_trace ON rag_retrieval_runs(trace_id);
CREATE INDEX idx_rag_retrieval_user_time ON rag_retrieval_runs(tenant_id, user_id, started_at DESC);

-- 一行代表一次重排运行；多批 LLM 调用共享同一个 ranking run。
CREATE TABLE rag_ranking_runs (
    id                    UUID PRIMARY KEY,
    retrieval_run_id      UUID         NOT NULL REFERENCES rag_retrieval_runs(id) ON DELETE CASCADE,
    configured_mode       VARCHAR(16)  NOT NULL, -- LLM/RERANK/DISABLED
    effective_mode        VARCHAR(16)  NOT NULL,
    model_config_id       BIGINT,
    ranking_config_version VARCHAR(64),
    candidate_count       INTEGER      NOT NULL DEFAULT 0,
    final_count           INTEGER      NOT NULL DEFAULT 0,
    candidate_hash        VARCHAR(128),
    fallback_reason       VARCHAR(500),
    status                VARCHAR(32)  NOT NULL DEFAULT 'RUNNING',
    latency_ms            BIGINT,
    started_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    finished_at           TIMESTAMPTZ
);
CREATE INDEX idx_rag_ranking_retrieval ON rag_ranking_runs(retrieval_run_id, started_at);

-- 一行代表一次 Query 改写、HyDE、重排批次、引用校验或答案生成模型调用。
CREATE TABLE rag_model_calls (
    id                    UUID PRIMARY KEY,
    trace_id              VARCHAR(64)  NOT NULL,
    retrieval_run_id      UUID         NOT NULL REFERENCES rag_retrieval_runs(id) ON DELETE CASCADE,
    ranking_run_id        UUID REFERENCES rag_ranking_runs(id) ON DELETE CASCADE,
    model_request_id      VARCHAR(64)  NOT NULL,
    provider_request_id   VARCHAR(128),
    call_purpose          VARCHAR(40)  NOT NULL,
    model_config_id       BIGINT,
    model_name            VARCHAR(200),
    provider_name         VARCHAR(100),
    input_hash            VARCHAR(128),
    output_hash           VARCHAR(128),
    prompt_tokens         INTEGER,
    completion_tokens     INTEGER,
    cost_points           DECIMAL(20, 6),
    status                VARCHAR(32)  NOT NULL DEFAULT 'RUNNING',
    latency_ms            BIGINT,
    error_summary         VARCHAR(1000),
    started_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    finished_at           TIMESTAMPTZ,
    CONSTRAINT uk_rag_model_request UNIQUE (model_request_id)
);
CREATE INDEX idx_rag_model_trace ON rag_model_calls(trace_id, started_at);
CREATE INDEX idx_rag_model_retrieval ON rag_model_calls(retrieval_run_id, started_at);
CREATE INDEX idx_rag_model_ranking ON rag_model_calls(ranking_run_id, started_at) WHERE ranking_run_id IS NOT NULL;

-- 一行代表一次显式降级，禁止故障时无记录地偷偷换链路或模型。
CREATE TABLE rag_fallback_events (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    trace_id              VARCHAR(64)  NOT NULL,
    retrieval_run_id      UUID         NOT NULL REFERENCES rag_retrieval_runs(id) ON DELETE CASCADE,
    ranking_run_id        UUID REFERENCES rag_ranking_runs(id) ON DELETE CASCADE,
    stage                 VARCHAR(40)  NOT NULL,
    configured_mode       VARCHAR(32),
    effective_mode        VARCHAR(32),
    reason_code           VARCHAR(64)  NOT NULL,
    reason_summary        VARCHAR(1000),
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_rag_fallback_retrieval ON rag_fallback_events(retrieval_run_id, created_at);

COMMENT ON TABLE rag_pipeline_versions IS 'RAG Pipeline 不可变配置快照；像每次发布使用的配方版本。';
COMMENT ON TABLE rag_retrieval_runs IS '一次知识检索的总运行记录，串联召回、重排、模型、审计和 Java 日志。';
COMMENT ON TABLE rag_ranking_runs IS '一次语义重排运行；记录配置模式、实际模式和降级原因。';
COMMENT ON TABLE rag_model_calls IS 'RAG 内每一次模型调用摘要；只存 Hash 和计量，不默认保存完整 Prompt/Chunk。';
COMMENT ON TABLE rag_fallback_events IS '检索或重排显式降级事件，保证故障切换可见、可审计。';

-- 回滚顺序：rag_fallback_events → rag_model_calls → rag_ranking_runs → rag_retrieval_runs → rag_pipeline_versions。

