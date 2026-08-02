-- 对话会话表
CREATE TABLE chat_sessions (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id       BIGINT NOT NULL REFERENCES users(id),
    title         VARCHAR(200),
    agent_id      BIGINT REFERENCES agents(id),
    workflow_id   BIGINT REFERENCES workflows(id),
    mode          VARCHAR(20) NOT NULL CHECK (mode IN ('CHAT', 'AGENT', 'WORKFLOW')),
    status        VARCHAR(20) DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    variables     JSONB,
    created_by    BIGINT,
    created_at    TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_by    BIGINT,
    updated_at    TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    deleted       INTEGER DEFAULT 0,
    version       INTEGER DEFAULT 0
);

CREATE INDEX idx_chat_sessions_user_id ON chat_sessions(user_id);
CREATE INDEX idx_chat_sessions_status ON chat_sessions(status);

-- 对话消息表
CREATE TABLE chat_messages (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    session_id    BIGINT NOT NULL REFERENCES chat_sessions(id),
    role          VARCHAR(20) NOT NULL CHECK (role IN ('USER', 'ASSISTANT', 'SYSTEM')),
    content       TEXT NOT NULL,
    metadata      JSONB,
    created_at    TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX idx_chat_messages_session_id ON chat_messages(session_id);
