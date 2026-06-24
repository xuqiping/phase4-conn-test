-- =============================================================================
-- V9__add_fixed_work_and_future_plans.sql
-- 用途：新增固定工作、未来计划模块，并把旧每日安排迁移为固定工作-每日类型
-- 说明：同时扩展 push_deliveries 以支持提醒类推送
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. 固定工作表：日/周/月周期的例行工作
-- -----------------------------------------------------------------------------
CREATE TABLE fixed_work_items (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    content TEXT NOT NULL,
    description TEXT,
    recurrence_type VARCHAR(16) NOT NULL,                       -- DAILY / WEEKLY / MONTHLY
    reminder_time TIME NOT NULL DEFAULT '09:00:00',             -- 当天触发时间
    reminder_days VARCHAR(64),                                  -- 逗号分隔：周 1-7，月 1-31
    timezone VARCHAR(64) DEFAULT 'Asia/Shanghai',               -- 用户时区
    reminder_enabled BOOLEAN NOT NULL DEFAULT FALSE,            -- 是否开启提醒
    push_platform VARCHAR(32),                                  -- FEISHU / DINGTALK / WECHAT_WORK / SLACK
    push_target_id VARCHAR(255),                                -- 目标 ID
    push_credential TEXT,                                       -- 加密凭据
    sort_order INT DEFAULT 0,
    created_by BIGINT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted INT NOT NULL DEFAULT 0
);

CREATE INDEX idx_fixed_work_items_user_type ON fixed_work_items(user_id, recurrence_type, deleted);
CREATE INDEX idx_fixed_work_items_reminder ON fixed_work_items(reminder_enabled, deleted);

-- -----------------------------------------------------------------------------
-- 2. 固定工作每日完成记录：按自然日记录是否完成
-- -----------------------------------------------------------------------------
CREATE TABLE fixed_work_completions (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    item_id BIGINT NOT NULL REFERENCES fixed_work_items(id),
    user_id BIGINT NOT NULL,
    completion_date DATE NOT NULL,
    completed BOOLEAN NOT NULL DEFAULT FALSE,
    completed_at TIMESTAMP WITH TIME ZONE,
    created_by BIGINT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted INT NOT NULL DEFAULT 0,
    CONSTRAINT idx_fixed_work_completions_unique UNIQUE (item_id, completion_date)
);

CREATE INDEX idx_fixed_work_completions_user_date ON fixed_work_completions(user_id, completion_date, deleted);

-- -----------------------------------------------------------------------------
-- 3. 未来计划表：一次性未来任务，支持定时提醒
-- -----------------------------------------------------------------------------
CREATE TABLE future_plans (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    content TEXT NOT NULL,
    description TEXT,
    scheduled_at TIMESTAMP WITH TIME ZONE NOT NULL,
    timezone VARCHAR(64) DEFAULT 'Asia/Shanghai',
    reminder_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    reminder_minutes_before INT DEFAULT 0,
    push_platform VARCHAR(32),
    push_target_id VARCHAR(255),
    push_credential TEXT,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',              -- PENDING / REMINDED / COMPLETED / CANCELLED
    sort_order INT DEFAULT 0,
    created_by BIGINT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted INT NOT NULL DEFAULT 0
);

CREATE INDEX idx_future_plans_user_status ON future_plans(user_id, status, deleted);
CREATE INDEX idx_future_plans_scheduled ON future_plans(scheduled_at, status, reminder_enabled);

-- -----------------------------------------------------------------------------
-- 4. 扩展推送记录表，区分报告推送与提醒推送
-- -----------------------------------------------------------------------------
ALTER TABLE push_deliveries ADD COLUMN source_type VARCHAR(16) DEFAULT 'REPORT';

-- -----------------------------------------------------------------------------
-- 5. 提醒推送记录表：用于未来计划和固定工作提醒的推送状态与重试
-- -----------------------------------------------------------------------------
CREATE TABLE reminder_deliveries (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    source_type VARCHAR(16) NOT NULL,                           -- FUTURE_PLAN / FIXED_WORK
    source_id BIGINT NOT NULL,                                  -- future_plans.id 或 fixed_work_items.id
    user_id BIGINT NOT NULL,
    platform VARCHAR(32) NOT NULL,
    target_id VARCHAR(255) NOT NULL,
    credential TEXT,                                            -- 加密凭据（用于重试）
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',              -- PENDING / SUCCESS / FAILED
    response TEXT,
    tried_count INT DEFAULT 0,
    created_by BIGINT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted INT NOT NULL DEFAULT 0
);

CREATE INDEX idx_reminder_deliveries_source ON reminder_deliveries(source_type, source_id, deleted);
CREATE INDEX idx_reminder_deliveries_status ON reminder_deliveries(status, tried_count, deleted);

-- -----------------------------------------------------------------------------
-- 6. 数据迁移：把旧的 work_plans 迁移为固定工作-每日类型
-- -----------------------------------------------------------------------------
INSERT INTO fixed_work_items (
    user_id,
    content,
    description,
    recurrence_type,
    reminder_time,
    reminder_days,
    timezone,
    reminder_enabled,
    sort_order,
    created_by,
    created_at,
    updated_by,
    updated_at,
    deleted
)
SELECT
    user_id,
    content,
    description,
    'DAILY',
    COALESCE(planned_start_time, TIME '09:00:00'),
    NULL,
    'Asia/Shanghai',
    FALSE,
    sort_order,
    created_by,
    created_at,
    updated_by,
    updated_at,
    deleted
FROM work_plans
WHERE deleted = 0;

-- 迁移今日已完成的计划到固定工作完成记录
INSERT INTO fixed_work_completions (
    item_id,
    user_id,
    completion_date,
    completed,
    completed_at,
    created_by,
    created_at,
    updated_by,
    updated_at,
    deleted
)
SELECT
    fwi.id,
    fwi.user_id,
    CURRENT_DATE,
    TRUE,
    wp.updated_at,
    wp.updated_by,
    wp.updated_at,
    wp.updated_by,
    wp.updated_at,
    0
FROM work_plans wp
JOIN fixed_work_items fwi ON fwi.user_id = wp.user_id AND fwi.content = wp.content
WHERE wp.deleted = 0 AND wp.completed = TRUE;

-- -----------------------------------------------------------------------------
-- 7. 更新默认报告模板占位符：兼容 {{plans}} 与 {{fixed_work}}
-- -----------------------------------------------------------------------------
UPDATE report_templates
SET content = REPLACE(content, '{{plans}}', '{{fixed_work}}')
WHERE content LIKE '%{{plans}}%';
