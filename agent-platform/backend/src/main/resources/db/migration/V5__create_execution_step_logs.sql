-- 步骤级执行日志
CREATE TABLE execution_step_logs (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    execution_id    BIGINT NOT NULL REFERENCES execution_logs(id),
    node_id         VARCHAR(50),
    skill_id        BIGINT REFERENCES skills(id),
    step_order      INTEGER,
    step_name       VARCHAR(200),
    action          VARCHAR(30) CHECK (action IN ('LLM_CALL', 'HTTP_REQUEST', 'CODE_EXECUTE', 'CONDITION_CHECK')),
    input_data      JSONB,
    output_data     JSONB,
    status          VARCHAR(20) CHECK (status IN ('RUNNING', 'SUCCESS', 'FAILED', 'SKIPPED')),
    duration        BIGINT,
    error_message   TEXT,
    created_at      TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX idx_execution_step_logs_execution_id ON execution_step_logs(execution_id);
