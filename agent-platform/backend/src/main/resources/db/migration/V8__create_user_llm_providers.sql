CREATE TABLE user_llm_providers (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id       BIGINT NOT NULL REFERENCES users(id),
    provider_name VARCHAR(50) NOT NULL,
    api_endpoint  VARCHAR(500),
    api_key_enc   TEXT,
    models        JSONB,
    status        VARCHAR(20) DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_by    BIGINT,
    created_at    TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_by    BIGINT,
    updated_at    TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    deleted       INTEGER DEFAULT 0,
    version       INTEGER DEFAULT 0
);

CREATE UNIQUE INDEX idx_ulp_user_name ON user_llm_providers(user_id, provider_name) WHERE deleted = 0;
CREATE INDEX idx_ulp_user_id ON user_llm_providers(user_id);
