-- RAG 评测中心：一套数据集包含多道固定考题，每次 Pipeline 运行产生一组可追溯结果。
CREATE TABLE IF NOT EXISTS rag_eval_datasets (
 id BIGSERIAL PRIMARY KEY, tenant_id BIGINT NOT NULL, kb_id BIGINT NOT NULL,
 name VARCHAR(200) NOT NULL, description TEXT, created_by BIGINT NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_rag_eval_dataset_scope ON rag_eval_datasets(tenant_id,kb_id);
CREATE TABLE IF NOT EXISTS rag_eval_cases (
 id BIGSERIAL PRIMARY KEY, dataset_id BIGINT NOT NULL REFERENCES rag_eval_datasets(id) ON DELETE CASCADE,
 query_type VARCHAR(40) NOT NULL, question TEXT NOT NULL, expected_chunk_ids JSONB NOT NULL DEFAULT '[]',
 forbidden_chunk_ids JSONB NOT NULL DEFAULT '[]', expected_claims JSONB NOT NULL DEFAULT '[]', metadata JSONB NOT NULL DEFAULT '{}'
);
CREATE INDEX IF NOT EXISTS idx_rag_eval_case_dataset ON rag_eval_cases(dataset_id);
CREATE TABLE IF NOT EXISTS rag_eval_runs (
 id BIGSERIAL PRIMARY KEY, dataset_id BIGINT NOT NULL REFERENCES rag_eval_datasets(id), pipeline_version VARCHAR(100) NOT NULL,
 status VARCHAR(30) NOT NULL, started_by BIGINT NOT NULL, started_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), finished_at TIMESTAMPTZ
);
CREATE TABLE IF NOT EXISTS rag_eval_results (
 id BIGSERIAL PRIMARY KEY, run_id BIGINT NOT NULL REFERENCES rag_eval_runs(id) ON DELETE CASCADE,
 case_id BIGINT NOT NULL REFERENCES rag_eval_cases(id), trace_id VARCHAR(64), metrics JSONB NOT NULL DEFAULT '{}',
 verdict VARCHAR(40), created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), UNIQUE(run_id,case_id)
);
