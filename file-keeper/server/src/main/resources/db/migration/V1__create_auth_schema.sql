-- =============================================================================
-- V1__create_auth_schema.sql
-- 用途：创建 File Keeper 授权与账号服务的基础业务表
-- 说明：这是 Flyway 首条迁移，包含用户、设备、授权、系统设置、审计等核心表
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 用户表：存储注册用户信息，支持邮箱/手机号两种登录方式
-- -----------------------------------------------------------------------------
CREATE TABLE users (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,          -- 主键，自增
    email VARCHAR(120),                                           -- 邮箱（可选，与手机号至少填一个）
    phone VARCHAR(32),                                            -- 手机号（可选）
    password_hash VARCHAR(120) NOT NULL,                          -- 密码哈希（BCrypt）
    role VARCHAR(32) NOT NULL DEFAULT 'user',                     -- 角色：super_admin / user
    status VARCHAR(32) NOT NULL DEFAULT 'pending_verification',   -- 状态：pending_verification / pending_review / active / disabled
    email_verified BOOLEAN NOT NULL DEFAULT FALSE,                -- 邮箱是否已验证
    phone_verified BOOLEAN NOT NULL DEFAULT FALSE,                -- 手机号是否已验证
    device_limit INT NOT NULL DEFAULT 1,                          -- 允许绑定的设备数量上限
    offline_cache_minutes INT NOT NULL DEFAULT 0,                 -- 离线缓存有效时长（分钟）
    created_by BIGINT,                                            -- 创建人 ID
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,  -- 创建时间
    updated_by BIGINT,                                            -- 最后更新人 ID
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,  -- 最后更新时间
    deleted INT NOT NULL DEFAULT 0,                               -- 逻辑删除标志：0 未删除 / 1 已删除
    CONSTRAINT uk_users_email UNIQUE (email),                     -- 邮箱唯一（NULL 可重复）
    CONSTRAINT uk_users_phone UNIQUE (phone),                     -- 手机号唯一（NULL 可重复）
    CONSTRAINT ck_users_contact CHECK (email IS NOT NULL OR phone IS NOT NULL),  -- 邮箱或手机号至少有一个
    CONSTRAINT ck_users_role CHECK (role IN ('super_admin', 'user')),
    CONSTRAINT ck_users_status CHECK (status IN ('pending_verification', 'pending_review', 'active', 'disabled'))
);

-- -----------------------------------------------------------------------------
-- 用户模块授权表：记录每个用户可使用的功能模块及有效期
-- -----------------------------------------------------------------------------
CREATE TABLE user_module_entitlements (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL,                                      -- 关联用户 ID
    module_code VARCHAR(32) NOT NULL,                             -- 模块编码：files / processes / clipboard 等
    enabled BOOLEAN NOT NULL DEFAULT TRUE,                        -- 该模块授权是否启用
    expires_at TIMESTAMP WITH TIME ZONE,                          -- 授权过期时间，NULL 表示永久
    created_by BIGINT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted INT NOT NULL DEFAULT 0,
    CONSTRAINT fk_user_module_entitlements_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT uk_user_module_entitlements_user_module UNIQUE (user_id, module_code),  -- 同一用户同一模块仅一条记录
    CONSTRAINT ck_user_module_entitlements_module CHECK (module_code IN ('files', 'processes', 'clipboard'))
);

CREATE INDEX idx_user_module_entitlements_user_id ON user_module_entitlements(user_id);  -- 按用户查询授权列表

-- -----------------------------------------------------------------------------
-- 用户设备表：记录用户绑定的桌面端设备
-- -----------------------------------------------------------------------------
CREATE TABLE user_devices (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL,                                      -- 关联用户 ID
    device_id VARCHAR(80) NOT NULL,                               -- 设备唯一标识（客户端生成）
    fingerprint_hash VARCHAR(128) NOT NULL,                       -- 设备指纹哈希，用于防篡改校验
    device_name VARCHAR(120),                                     -- 设备显示名称（后续 V2 扩至 255）
    status VARCHAR(32) NOT NULL DEFAULT 'active',                 -- 设备状态：active / disabled
    last_seen_at TIMESTAMP WITH TIME ZONE,                        -- 最后一次在线时间
    created_by BIGINT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted INT NOT NULL DEFAULT 0,
    CONSTRAINT fk_user_devices_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT uk_user_devices_user_device UNIQUE (user_id, device_id),  -- 同一用户下设备 ID 唯一
    CONSTRAINT ck_user_devices_status CHECK (status IN ('active', 'disabled'))
);

CREATE INDEX idx_user_devices_user_id ON user_devices(user_id);       -- 按用户查询设备列表
CREATE INDEX idx_user_devices_device_id ON user_devices(device_id);   -- 按设备 ID 反查

-- -----------------------------------------------------------------------------
-- 匿名设备试用表：未注册用户设备的免费试用记录
-- -----------------------------------------------------------------------------
CREATE TABLE anonymous_device_trials (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    device_id VARCHAR(80) NOT NULL,                               -- 设备唯一标识
    fingerprint_hash VARCHAR(128) NOT NULL,                       -- 设备指纹哈希
    device_name VARCHAR(120),                                     -- 设备显示名称（后续 V2 扩至 255）
    trial_started_at TIMESTAMP WITH TIME ZONE NOT NULL,           -- 试用开始时间
    trial_expires_at TIMESTAMP WITH TIME ZONE NOT NULL,           -- 试用过期时间
    free_module_code VARCHAR(32),                                 -- 用户选择的免费模块：files / processes / clipboard
    free_module_selected_at TIMESTAMP WITH TIME ZONE,             -- 选择免费模块的时间
    last_free_module_changed_at TIMESTAMP WITH TIME ZONE,         -- 上次更换免费模块的时间
    status VARCHAR(32) NOT NULL DEFAULT 'active',                 -- 状态：active / disabled
    created_by BIGINT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted INT NOT NULL DEFAULT 0,
    CONSTRAINT uk_anonymous_device_trials_device UNIQUE (device_id),  -- 每个设备仅一条试用记录
    CONSTRAINT ck_anonymous_device_trials_module CHECK (free_module_code IS NULL OR free_module_code IN ('files', 'processes', 'clipboard')),
    CONSTRAINT ck_anonymous_device_trials_status CHECK (status IN ('active', 'disabled'))
);

CREATE INDEX idx_anonymous_device_trials_device_id ON anonymous_device_trials(device_id);  -- 按设备 ID 查询试用状态

-- -----------------------------------------------------------------------------
-- 系统设置表：存储后台可动态调整的配置项（如站点开关、默认值等）
-- -----------------------------------------------------------------------------
CREATE TABLE system_settings (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    setting_key VARCHAR(120) NOT NULL,                            -- 配置键
    setting_value VARCHAR(500),                                   -- 配置值
    description VARCHAR(500),                                     -- 配置项说明
    created_by BIGINT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted INT NOT NULL DEFAULT 0,
    CONSTRAINT uk_system_settings_key UNIQUE (setting_key)        -- 配置键全局唯一
);

-- -----------------------------------------------------------------------------
-- 管理后台审计日志表：记录管理员的关键操作，用于安全审计
-- -----------------------------------------------------------------------------
CREATE TABLE admin_audit_logs (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    admin_user_id BIGINT,                                         -- 操作管理员用户 ID
    action VARCHAR(120) NOT NULL,                                 -- 操作动作，如 USER_DISABLE / LICENSE_GRANT
    target_type VARCHAR(80) NOT NULL,                             -- 操作对象类型，如 USER / DEVICE / LICENSE
    target_id VARCHAR(120),                                       -- 操作对象 ID
    detail TEXT,                                                  -- 操作详情（JSON 或文本）
    ip_address VARCHAR(64),                                       -- 操作来源 IP
    created_by BIGINT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted INT NOT NULL DEFAULT 0,
    CONSTRAINT fk_admin_audit_logs_admin_user FOREIGN KEY (admin_user_id) REFERENCES users(id)
);

CREATE INDEX idx_admin_audit_logs_admin_user_id ON admin_audit_logs(admin_user_id);  -- 按管理员查询操作记录
CREATE INDEX idx_admin_audit_logs_target ON admin_audit_logs(target_type, target_id); -- 按操作对象查询
