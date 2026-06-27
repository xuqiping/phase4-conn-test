-- =============================================================================
-- V10__add_ai_config.sql
-- 用途：新增 AI 模型配置表，并将 AI 能力纳入模块授权体系
-- 说明：
--   1. 扩展 module_code CHECK 约束，加入 'ai' 模块
--   2. 新增 ai_configs 表，按用户存储多条 AI 配置
--   3. report_configs 增加 ai_config_id 外键，用于指定报告使用的 AI 配置
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. 扩展用户模块授权表的 CHECK 约束，加入 'ai'
-- -----------------------------------------------------------------------------
ALTER TABLE user_module_entitlements
    DROP CONSTRAINT IF EXISTS ck_user_module_entitlements_module;

ALTER TABLE user_module_entitlements
    ADD CONSTRAINT ck_user_module_entitlements_module
        CHECK (module_code IN ('files', 'processes', 'clipboard', 'work-report', 'ai'));

-- -----------------------------------------------------------------------------
-- 2. 扩展匿名设备试用表的免费模块 CHECK 约束，加入 'ai'
-- -----------------------------------------------------------------------------
ALTER TABLE anonymous_device_trials
    DROP CONSTRAINT IF EXISTS ck_anonymous_device_trials_module;

ALTER TABLE anonymous_device_trials
    ADD CONSTRAINT ck_anonymous_device_trials_module
        CHECK (free_module_code IS NULL OR free_module_code IN ('files', 'processes', 'clipboard', 'work-report', 'ai'));

-- -----------------------------------------------------------------------------
-- 3. AI 模型配置表：用户在桌面端自行配置的大模型连接信息
-- -----------------------------------------------------------------------------
CREATE TABLE ai_configs (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),                 -- 所属用户
    name VARCHAR(64) NOT NULL,                                    -- 配置名称，用户自定义
    provider VARCHAR(32) NOT NULL,                                -- 提供商：qwen / doubao / claude
    model VARCHAR(64) NOT NULL,                                   -- 模型名，如 qwen-turbo
    api_key_enc VARCHAR(512),                                     -- 加密后的 API Key
    endpoint VARCHAR(256),                                        -- 自定义 endpoint，为空使用 Provider 默认
    max_tokens INT NOT NULL DEFAULT 2048,                         -- 最大 token 数
    timeout_seconds INT NOT NULL DEFAULT 30,                      -- 调用超时（秒）
    is_default BOOLEAN NOT NULL DEFAULT FALSE,                    -- 是否为该用户默认配置
    enabled BOOLEAN NOT NULL DEFAULT TRUE,                        -- 是否启用
    created_by BIGINT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted INT NOT NULL DEFAULT 0,
    CONSTRAINT uq_ai_configs_user_name UNIQUE (user_id, name)
);

CREATE INDEX idx_ai_configs_user_id ON ai_configs(user_id, deleted);           -- 查询用户配置列表
CREATE INDEX idx_ai_configs_user_default ON ai_configs(user_id, is_default, deleted);  -- 查询默认配置

-- -----------------------------------------------------------------------------
-- 4. 报告配置表增加 ai_config_id，支持为不同报告指定不同 AI 配置
-- -----------------------------------------------------------------------------
ALTER TABLE report_configs ADD COLUMN ai_config_id BIGINT REFERENCES ai_configs(id);

CREATE INDEX idx_report_configs_ai_config ON report_configs(ai_config_id);
