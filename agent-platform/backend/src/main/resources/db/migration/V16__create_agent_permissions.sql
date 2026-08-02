CREATE TABLE agent_permissions (
    id BIGSERIAL PRIMARY KEY,
    agent_id BIGINT NOT NULL REFERENCES agents(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    can_use BOOLEAN NOT NULL DEFAULT TRUE,
    can_read_prompt BOOLEAN NOT NULL DEFAULT FALSE,
    can_copy BOOLEAN NOT NULL DEFAULT FALSE,
    granted_by BIGINT REFERENCES users(id),
    created_by BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted INTEGER NOT NULL DEFAULT 0,
    version INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT uk_agent_permissions_agent_user UNIQUE (agent_id, user_id)
);

CREATE INDEX idx_agent_permissions_user ON agent_permissions(user_id) WHERE deleted = 0;
CREATE INDEX idx_agent_permissions_agent ON agent_permissions(agent_id) WHERE deleted = 0;
