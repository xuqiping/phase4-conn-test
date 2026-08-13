-- RAG evaluation center. One dataset is a tenant/KB-scoped golden test suite.
CREATE TABLE IF NOT EXISTS rag_eval_datasets (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    kb_id BIGINT NOT NULL,
    name VARCHAR(200) NOT NULL,
    description TEXT,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (tenant_id, kb_id, name)
);
CREATE INDEX IF NOT EXISTS idx_rag_eval_dataset_scope ON rag_eval_datasets(tenant_id, kb_id);

-- One row is one fixed evaluation question and its expected/forbidden evidence IDs.
CREATE TABLE IF NOT EXISTS rag_eval_cases (
    id BIGSERIAL PRIMARY KEY,
    dataset_id BIGINT NOT NULL REFERENCES rag_eval_datasets(id) ON DELETE CASCADE,
    query_type VARCHAR(40) NOT NULL,
    question TEXT NOT NULL,
    expected_chunk_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    forbidden_chunk_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    expected_claims JSONB NOT NULL DEFAULT '[]'::jsonb,
    answerable BOOLEAN NOT NULL DEFAULT TRUE,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_rag_eval_case_dataset ON rag_eval_cases(dataset_id, id);

-- One row is one immutable pipeline evaluation execution.
CREATE TABLE IF NOT EXISTS rag_eval_runs (
    id BIGSERIAL PRIMARY KEY,
    dataset_id BIGINT NOT NULL REFERENCES rag_eval_datasets(id),
    pipeline_version VARCHAR(100) NOT NULL,
    status VARCHAR(30) NOT NULL,
    started_by BIGINT NOT NULL,
    started_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    finished_at TIMESTAMPTZ,
    summary_metrics JSONB NOT NULL DEFAULT '{}'::jsonb,
    error_summary VARCHAR(1000)
);
CREATE INDEX IF NOT EXISTS idx_rag_eval_run_status ON rag_eval_runs(status, started_at);

-- One row stores the metrics and trace link for one case in one run.
CREATE TABLE IF NOT EXISTS rag_eval_results (
    id BIGSERIAL PRIMARY KEY,
    run_id BIGINT NOT NULL REFERENCES rag_eval_runs(id) ON DELETE CASCADE,
    case_id BIGINT NOT NULL REFERENCES rag_eval_cases(id),
    trace_id VARCHAR(64),
    metrics JSONB NOT NULL DEFAULT '{}'::jsonb,
    verdict VARCHAR(40),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(run_id, case_id)
);
CREATE INDEX IF NOT EXISTS idx_rag_eval_result_trace ON rag_eval_results(trace_id) WHERE trace_id IS NOT NULL;
