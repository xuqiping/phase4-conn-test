-- =============================================================================
-- V11__extract_push_target_and_credential.sql
-- 用途：把报告配置下的推送目标与凭据提取为独立可复用配置
-- 说明：
--   1. 新增 push_credentials（凭据）、push_targets（推送目标）表
--   2. 新增 report_config_push_targets 中间表，支持报告配置复用推送目标
--   3. fixed_work_items / future_plans 增加 push_target_id，旧内联字段重命名为 legacy_*
--   4. 迁移旧数据：原 report_push_targets 逐行拆成凭据+目标+关联；固定工作/未来计划
--      中内联的凭据也拆成独立目标
--   5. 本脚本使用纯 SQL（无 PL/pgSQL DO 块），兼容 PostgreSQL 与 H2（测试用）
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. 凭据表：用户可复用的推送凭据（如飞书 appId/appSecret、钉钉 secret）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS push_credentials (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    name VARCHAR(128) NOT NULL,
    platform VARCHAR(32) NOT NULL,
    credential_enc TEXT,                                          -- 加密后的凭据，保持与原 credential 字段一致
    created_by BIGINT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted INT NOT NULL DEFAULT 0,
    CONSTRAINT uq_push_credentials_user_name UNIQUE (user_id, name)
);

CREATE INDEX IF NOT EXISTS idx_push_credentials_user ON push_credentials(user_id, deleted);

-- -----------------------------------------------------------------------------
-- 2. 推送目标表：用户可复用的推送目标（平台 + 目标类型 + 目标 ID + 引用凭据）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS push_targets (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    name VARCHAR(128) NOT NULL,
    platform VARCHAR(32) NOT NULL,
    target_type VARCHAR(32) NOT NULL,
    target_id VARCHAR(255) NOT NULL,
    credential_id BIGINT NOT NULL REFERENCES push_credentials(id),
    created_by BIGINT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted INT NOT NULL DEFAULT 0,
    CONSTRAINT uq_push_targets_user_name UNIQUE (user_id, name)
);

CREATE INDEX IF NOT EXISTS idx_push_targets_user ON push_targets(user_id, deleted);
CREATE INDEX IF NOT EXISTS idx_push_targets_credential ON push_targets(credential_id, deleted);

-- -----------------------------------------------------------------------------
-- 3. 报告配置与推送目标关联表（many-to-many，含软删）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS report_config_push_targets (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    config_id BIGINT NOT NULL REFERENCES report_configs(id),
    target_id BIGINT NOT NULL REFERENCES push_targets(id),
    created_by BIGINT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted INT NOT NULL DEFAULT 0,
    CONSTRAINT uq_report_config_push_targets UNIQUE (config_id, target_id)
);

CREATE INDEX IF NOT EXISTS idx_report_config_push_targets_config ON report_config_push_targets(config_id, deleted);
CREATE INDEX IF NOT EXISTS idx_report_config_push_targets_target ON report_config_push_targets(target_id, deleted);

-- -----------------------------------------------------------------------------
-- 4. 固定工作、未来计划增加 push_target_id 外键，旧内联字段重命名保留历史数据
-- -----------------------------------------------------------------------------
ALTER TABLE fixed_work_items RENAME COLUMN push_target_id TO legacy_push_target_id;
ALTER TABLE fixed_work_items RENAME COLUMN push_platform TO legacy_push_platform;
ALTER TABLE fixed_work_items RENAME COLUMN push_credential TO legacy_push_credential;
ALTER TABLE fixed_work_items ADD COLUMN IF NOT EXISTS push_target_id BIGINT REFERENCES push_targets(id);

ALTER TABLE future_plans RENAME COLUMN push_target_id TO legacy_push_target_id;
ALTER TABLE future_plans RENAME COLUMN push_platform TO legacy_push_platform;
ALTER TABLE future_plans RENAME COLUMN push_credential TO legacy_push_credential;
ALTER TABLE future_plans ADD COLUMN IF NOT EXISTS push_target_id BIGINT REFERENCES push_targets(id);

CREATE INDEX IF NOT EXISTS idx_fixed_work_items_push_target ON fixed_work_items(push_target_id);
CREATE INDEX IF NOT EXISTS idx_future_plans_push_target ON future_plans(push_target_id);

-- -----------------------------------------------------------------------------
-- 4.1 提醒推送记录增加 push_target_id，用于追溯复用的推送目标
-- -----------------------------------------------------------------------------
ALTER TABLE reminder_deliveries ADD COLUMN IF NOT EXISTS push_target_id BIGINT;

-- -----------------------------------------------------------------------------
-- 5. 迁移旧报告推送目标数据
--    每一行 report_push_targets 拆成：1 条 push_credentials + 1 条 push_targets + 1 条关联
--    凭据保持原值迁移（已是加密或明文，不尝试解密）
-- -----------------------------------------------------------------------------
INSERT INTO push_credentials (user_id, name, platform, credential_enc, created_by, created_at, updated_by, updated_at, deleted)
SELECT
    c.user_id,
    '迁移凭据 ' || t.id,
    t.platform,
    t.credential,
    t.created_by,
    t.created_at,
    t.updated_by,
    t.updated_at,
    0
FROM report_push_targets t
JOIN report_configs c ON c.id = t.config_id
WHERE t.deleted = 0;

INSERT INTO push_targets (user_id, name, platform, target_type, target_id, credential_id, created_by, created_at, updated_by, updated_at, deleted)
SELECT
    c.user_id,
    '迁移目标 ' || t.id || ' (' || t.target_id || ')',
    t.platform,
    t.target_type,
    t.target_id,
    pc.id,
    t.created_by,
    t.created_at,
    t.updated_by,
    t.updated_at,
    0
FROM report_push_targets t
JOIN report_configs c ON c.id = t.config_id
JOIN push_credentials pc ON pc.name = '迁移凭据 ' || t.id
WHERE t.deleted = 0;

INSERT INTO report_config_push_targets (config_id, target_id, created_by, created_at, updated_by, updated_at, deleted)
SELECT
    t.config_id,
    pt.id,
    t.created_by,
    t.created_at,
    t.updated_by,
    t.updated_at,
    0
FROM report_push_targets t
JOIN push_targets pt ON pt.name = '迁移目标 ' || t.id || ' (' || t.target_id || ')'
WHERE t.deleted = 0;

-- -----------------------------------------------------------------------------
-- 5.1 修正 push_deliveries 外键：原指向 report_push_targets，改为指向 push_targets
-- -----------------------------------------------------------------------------
ALTER TABLE push_deliveries ADD COLUMN IF NOT EXISTS new_target_id BIGINT REFERENCES push_targets(id);

UPDATE push_deliveries d
SET new_target_id = (
    SELECT pt.id
    FROM report_push_targets t
    JOIN push_targets pt ON pt.name = '迁移目标 ' || t.id || ' (' || t.target_id || ')'
    WHERE d.target_id = t.id
);

ALTER TABLE push_deliveries DROP COLUMN target_id;
ALTER TABLE push_deliveries RENAME COLUMN new_target_id TO target_id;
ALTER TABLE push_deliveries ALTER COLUMN target_id SET NOT NULL;

-- -----------------------------------------------------------------------------
-- 6. 迁移固定工作中内联的推送凭据/目标
-- -----------------------------------------------------------------------------
INSERT INTO push_credentials (user_id, name, platform, credential_enc, created_by, created_at, updated_by, updated_at, deleted)
SELECT
    f.user_id,
    '固定工作凭据 ' || f.id,
    f.legacy_push_platform,
    f.legacy_push_credential,
    f.created_by,
    f.created_at,
    f.updated_by,
    f.updated_at,
    0
FROM fixed_work_items f
WHERE f.legacy_push_platform IS NOT NULL AND f.legacy_push_platform <> ''
  AND f.legacy_push_target_id IS NOT NULL
  AND f.deleted = 0;

INSERT INTO push_targets (user_id, name, platform, target_type, target_id, credential_id, created_by, created_at, updated_by, updated_at, deleted)
SELECT
    f.user_id,
    '固定工作目标 ' || f.id || ' (' || f.legacy_push_target_id || ')',
    f.legacy_push_platform,
    'GROUP',
    f.legacy_push_target_id,
    pc.id,
    f.created_by,
    f.created_at,
    f.updated_by,
    f.updated_at,
    0
FROM fixed_work_items f
JOIN push_credentials pc ON pc.name = '固定工作凭据 ' || f.id
WHERE f.legacy_push_platform IS NOT NULL AND f.legacy_push_platform <> ''
  AND f.legacy_push_target_id IS NOT NULL
  AND f.deleted = 0;

UPDATE fixed_work_items f
SET push_target_id = (
    SELECT pt.id
    FROM push_targets pt
    WHERE pt.name = '固定工作目标 ' || f.id || ' (' || f.legacy_push_target_id || ')'
)
WHERE f.legacy_push_platform IS NOT NULL AND f.legacy_push_platform <> ''
  AND f.legacy_push_target_id IS NOT NULL
  AND f.deleted = 0;

-- -----------------------------------------------------------------------------
-- 7. 迁移未来计划中内联的推送凭据/目标
-- -----------------------------------------------------------------------------
INSERT INTO push_credentials (user_id, name, platform, credential_enc, created_by, created_at, updated_by, updated_at, deleted)
SELECT
    p.user_id,
    '未来计划凭据 ' || p.id,
    p.legacy_push_platform,
    p.legacy_push_credential,
    p.created_by,
    p.created_at,
    p.updated_by,
    p.updated_at,
    0
FROM future_plans p
WHERE p.legacy_push_platform IS NOT NULL AND p.legacy_push_platform <> ''
  AND p.legacy_push_target_id IS NOT NULL
  AND p.deleted = 0;

INSERT INTO push_targets (user_id, name, platform, target_type, target_id, credential_id, created_by, created_at, updated_by, updated_at, deleted)
SELECT
    p.user_id,
    '未来计划目标 ' || p.id || ' (' || p.legacy_push_target_id || ')',
    p.legacy_push_platform,
    'GROUP',
    p.legacy_push_target_id,
    pc.id,
    p.created_by,
    p.created_at,
    p.updated_by,
    p.updated_at,
    0
FROM future_plans p
JOIN push_credentials pc ON pc.name = '未来计划凭据 ' || p.id
WHERE p.legacy_push_platform IS NOT NULL AND p.legacy_push_platform <> ''
  AND p.legacy_push_target_id IS NOT NULL
  AND p.deleted = 0;

UPDATE future_plans p
SET push_target_id = (
    SELECT pt.id
    FROM push_targets pt
    WHERE pt.name = '未来计划目标 ' || p.id || ' (' || p.legacy_push_target_id || ')'
)
WHERE p.legacy_push_platform IS NOT NULL AND p.legacy_push_platform <> ''
  AND p.legacy_push_target_id IS NOT NULL
  AND p.deleted = 0;
