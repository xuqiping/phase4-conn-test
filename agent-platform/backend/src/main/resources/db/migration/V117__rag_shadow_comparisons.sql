-- Sampled Champion/Challenger retrieval comparisons; no query or chunk body is stored.
CREATE TABLE IF NOT EXISTS rag_shadow_comparisons (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    kb_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    champion_trace_id VARCHAR(64),
    challenger_trace_id VARCHAR(64),
    champion_version VARCHAR(64) NOT NULL,
    challenger_version VARCHAR(64) NOT NULL,
    status VARCHAR(30) NOT NULL,
    ranked_chunk_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    cost DECIMAL(18,6) NOT NULL DEFAULT 0,
    error_summary VARCHAR(300),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_rag_shadow_scope ON rag_shadow_comparisons(tenant_id,kb_id,created_at DESC);
CREATE INDEX IF NOT EXISTS idx_rag_shadow_champion_trace ON rag_shadow_comparisons(champion_trace_id) WHERE champion_trace_id IS NOT NULL;
