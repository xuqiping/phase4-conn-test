ALTER TABLE execution_logs
    ADD COLUMN parent_execution_id BIGINT,
    ADD COLUMN root_execution_id BIGINT,
    ADD COLUMN source_type VARCHAR(30),
    ADD COLUMN source_id BIGINT,
    ADD COLUMN node_id VARCHAR(100),
    ADD COLUMN external_thread_id VARCHAR(200),
    ADD COLUMN checkpoint_ref VARCHAR(500),
    ADD COLUMN trace_id VARCHAR(100);

CREATE INDEX idx_execution_logs_root_execution_id ON execution_logs(root_execution_id);
CREATE INDEX idx_execution_logs_parent_execution_id ON execution_logs(parent_execution_id);
CREATE INDEX idx_execution_logs_trace_id ON execution_logs(trace_id);
