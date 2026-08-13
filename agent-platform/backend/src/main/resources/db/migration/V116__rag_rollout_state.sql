-- Restart-safe RAG rollout state. One row stores current and rollback target for one KB.
CREATE TABLE IF NOT EXISTS rag_rollout_states (
    kb_id BIGINT PRIMARY KEY,
    current_percentage INTEGER NOT NULL,
    current_config_version VARCHAR(64) NOT NULL,
    current_operator_id BIGINT NOT NULL,
    previous_percentage INTEGER,
    previous_config_version VARCHAR(64),
    previous_operator_id BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CHECK (current_percentage IN (0, 5, 20, 50, 100)),
    CHECK (previous_percentage IS NULL OR previous_percentage IN (0, 5, 20, 50, 100))
);
