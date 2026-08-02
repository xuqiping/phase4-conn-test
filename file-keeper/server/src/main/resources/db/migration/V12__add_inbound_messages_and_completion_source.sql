-- =============================================================================
-- V12__add_inbound_messages_and_completion_source.sql
-- 用途：新增互动收件箱，扩展固定工作完成记录来源与工作记录溯源
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1. 互动收件箱：所有 IM 原始输入先进入此处
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS inbound_messages (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    platform VARCHAR(32) NOT NULL,                              -- FEISHU / DINGTALK / WECHAT_WORK / SLACK
    platform_message_id VARCHAR(255) NOT NULL,                  -- 平台消息 ID，用于幂等
    sender_id VARCHAR(255),                                     -- 平台用户 ID
    sender_name VARCHAR(255),                                   -- 平台用户昵称
    raw_text TEXT NOT NULL,
    intent VARCHAR(64),                                         -- complete_fixed_work / add_work_log / add_inspiration / help / unknown
    confidence DECIMAL(3, 2) NOT NULL DEFAULT 0.00,             -- 0.00 - 1.00
    parsed_payload JSONB,                                       -- 解析后的结构化数据
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',              -- PENDING / CONFIRMED / IGNORED / FAILED
    target_module VARCHAR(64),                                  -- fixed_work / work_log / inspiration
    target_id BIGINT,
    created_by BIGINT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted INT NOT NULL DEFAULT 0,
    CONSTRAINT idx_inbound_messages_unique UNIQUE (platform, platform_message_id)
);

CREATE INDEX IF NOT EXISTS idx_inbound_messages_user_status ON inbound_messages(user_id, status, deleted);
CREATE INDEX IF NOT EXISTS idx_inbound_messages_created_at ON inbound_messages(user_id, created_at);

-- ---------------------------------------------------------------------------
-- 2. 扩展固定工作完成记录：记录完成来源
-- ---------------------------------------------------------------------------
ALTER TABLE fixed_work_completions ADD COLUMN IF NOT EXISTS completion_source VARCHAR(32) DEFAULT 'DESKTOP';

-- ---------------------------------------------------------------------------
-- 3. 扩展工作记录：支持 IM 消息溯源
-- ---------------------------------------------------------------------------
ALTER TABLE work_logs ADD COLUMN IF NOT EXISTS platform_message_id VARCHAR(255);
