-- =============================================================================
-- V13__add_inspiration_notes.sql
-- 用途：新增灵感随记表，支持 NLP 自动采集与桌面端管理
-- =============================================================================

CREATE TABLE IF NOT EXISTS inspiration_notes (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    content TEXT NOT NULL,
    tags TEXT ARRAY,                                          -- 自动/手动标签
    source VARCHAR(32),                                       -- IM / DESKTOP / REPORT
    platform_message_id VARCHAR(255),                         -- IM 消息溯源
    report_config_ids BIGINT ARRAY,                           -- 已纳入的汇报配置
    reviewed_at TIMESTAMP WITH TIME ZONE,                     -- 人工审阅/归档时间
    created_by BIGINT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted INT NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_inspiration_notes_user_deleted ON inspiration_notes(user_id, deleted);
CREATE INDEX IF NOT EXISTS idx_inspiration_notes_created_at ON inspiration_notes(user_id, created_at DESC);
