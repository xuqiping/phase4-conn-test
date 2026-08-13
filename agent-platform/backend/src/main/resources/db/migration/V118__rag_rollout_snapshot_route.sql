-- Keep the OpenSearch read-route snapshot paired with each rollout state for deterministic rollback.
ALTER TABLE rag_rollout_states
    ADD COLUMN IF NOT EXISTS current_snapshot_id VARCHAR(64),
    ADD COLUMN IF NOT EXISTS previous_snapshot_id VARCHAR(64);
