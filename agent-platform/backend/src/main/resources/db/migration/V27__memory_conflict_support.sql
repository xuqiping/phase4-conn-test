-- 记忆冲突解决支持（设计 §5）
-- 个人记忆知识库（含记忆冲突解决）：embed 聚类分块 + 冲突表
ALTER TABLE user_memories ADD COLUMN block_label VARCHAR(100);
ALTER TABLE user_memories ADD COLUMN embedding halfvec(2048);   -- 记忆聚类专用（与 knowledge_embeddings_doubao 无关）
ALTER TABLE user_memories ADD COLUMN conflict_id BIGINT;
CREATE INDEX idx_user_memories_block ON user_memories(user_id, block_label);

CREATE TABLE memory_conflicts (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id              BIGINT NOT NULL REFERENCES users(id),
    session_id           BIGINT REFERENCES chat_sessions(id),
    block_label          VARCHAR(100),
    new_memory           JSONB NOT NULL,
    new_embedding        halfvec(2048),
    existing_memory_ids  BIGINT[] NOT NULL DEFAULT '{}',
    ask_text             TEXT,
    status               VARCHAR(20) NOT NULL DEFAULT 'PENDING'
                         CHECK (status IN ('PENDING','FLAGGED','RESOLVED')),
    resolution           VARCHAR(20),
    created_at           TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    resolved_at          TIMESTAMP WITH TIME ZONE,
    expires_at           TIMESTAMP WITH TIME ZONE
);
CREATE INDEX idx_memconf_session_pending ON memory_conflicts(session_id) WHERE status='PENDING';
CREATE INDEX idx_memconf_user_flagged ON memory_conflicts(user_id) WHERE status='FLAGGED';
CREATE UNIQUE INDEX uq_memconf_session_pending ON memory_conflicts(session_id) WHERE status='PENDING';
