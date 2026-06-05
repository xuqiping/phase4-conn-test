-- 用户长期记忆
CREATE TABLE user_memories (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id       BIGINT NOT NULL REFERENCES users(id),
    category      VARCHAR(50) CHECK (category IN ('PREFERENCE', 'FACT', 'FEEDBACK')),
    key           VARCHAR(200),
    value         TEXT,
    source        VARCHAR(50) CHECK (source IN ('INFERRED', 'EXPLICIT', 'SYSTEM')),
    confidence    DECIMAL(3,2) DEFAULT 1.0,
    created_at    TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at    TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX idx_user_memories_user_id ON user_memories(user_id);
CREATE UNIQUE INDEX idx_user_memories_user_key ON user_memories(user_id, key);

-- LLM供应商配置
CREATE TABLE llm_providers (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name          VARCHAR(50) NOT NULL UNIQUE,
    display_name  VARCHAR(100),
    api_endpoint  VARCHAR(500),
    api_key_enc   TEXT,
    models        JSONB,
    config        JSONB,
    status        VARCHAR(20) DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
    sort_order    INTEGER DEFAULT 0,
    created_by    BIGINT,
    created_at    TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_by    BIGINT,
    updated_at    TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    deleted       INTEGER DEFAULT 0,
    version       INTEGER DEFAULT 0
);
