CREATE TABLE users (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email VARCHAR(120),
    phone VARCHAR(32),
    password_hash VARCHAR(120) NOT NULL,
    role VARCHAR(32) NOT NULL DEFAULT 'user',
    status VARCHAR(32) NOT NULL DEFAULT 'pending_verification',
    email_verified BOOLEAN NOT NULL DEFAULT FALSE,
    phone_verified BOOLEAN NOT NULL DEFAULT FALSE,
    device_limit INT NOT NULL DEFAULT 1,
    offline_cache_minutes INT NOT NULL DEFAULT 0,
    created_by BIGINT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted INT NOT NULL DEFAULT 0,
    CONSTRAINT uk_users_email UNIQUE (email),
    CONSTRAINT uk_users_phone UNIQUE (phone),
    CONSTRAINT ck_users_contact CHECK (email IS NOT NULL OR phone IS NOT NULL),
    CONSTRAINT ck_users_role CHECK (role IN ('super_admin', 'user')),
    CONSTRAINT ck_users_status CHECK (status IN ('pending_verification', 'pending_review', 'active', 'disabled'))
);

CREATE TABLE user_module_entitlements (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL,
    module_code VARCHAR(32) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    expires_at TIMESTAMP WITH TIME ZONE,
    created_by BIGINT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted INT NOT NULL DEFAULT 0,
    CONSTRAINT fk_user_module_entitlements_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT uk_user_module_entitlements_user_module UNIQUE (user_id, module_code),
    CONSTRAINT ck_user_module_entitlements_module CHECK (module_code IN ('files', 'processes', 'clipboard'))
);

CREATE INDEX idx_user_module_entitlements_user_id ON user_module_entitlements(user_id);

CREATE TABLE user_devices (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL,
    device_id VARCHAR(80) NOT NULL,
    fingerprint_hash VARCHAR(128) NOT NULL,
    device_name VARCHAR(120),
    status VARCHAR(32) NOT NULL DEFAULT 'active',
    last_seen_at TIMESTAMP WITH TIME ZONE,
    created_by BIGINT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted INT NOT NULL DEFAULT 0,
    CONSTRAINT fk_user_devices_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT uk_user_devices_user_device UNIQUE (user_id, device_id),
    CONSTRAINT ck_user_devices_status CHECK (status IN ('active', 'disabled'))
);

CREATE INDEX idx_user_devices_user_id ON user_devices(user_id);
CREATE INDEX idx_user_devices_device_id ON user_devices(device_id);

CREATE TABLE anonymous_device_trials (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    device_id VARCHAR(80) NOT NULL,
    fingerprint_hash VARCHAR(128) NOT NULL,
    device_name VARCHAR(120),
    trial_started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    trial_expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    free_module_code VARCHAR(32),
    free_module_selected_at TIMESTAMP WITH TIME ZONE,
    last_free_module_changed_at TIMESTAMP WITH TIME ZONE,
    status VARCHAR(32) NOT NULL DEFAULT 'active',
    created_by BIGINT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted INT NOT NULL DEFAULT 0,
    CONSTRAINT uk_anonymous_device_trials_device UNIQUE (device_id),
    CONSTRAINT ck_anonymous_device_trials_module CHECK (free_module_code IS NULL OR free_module_code IN ('files', 'processes', 'clipboard')),
    CONSTRAINT ck_anonymous_device_trials_status CHECK (status IN ('active', 'disabled'))
);

CREATE INDEX idx_anonymous_device_trials_device_id ON anonymous_device_trials(device_id);

CREATE TABLE system_settings (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    setting_key VARCHAR(120) NOT NULL,
    setting_value VARCHAR(500),
    description VARCHAR(500),
    created_by BIGINT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted INT NOT NULL DEFAULT 0,
    CONSTRAINT uk_system_settings_key UNIQUE (setting_key)
);

CREATE TABLE admin_audit_logs (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    admin_user_id BIGINT,
    action VARCHAR(120) NOT NULL,
    target_type VARCHAR(80) NOT NULL,
    target_id VARCHAR(120),
    detail TEXT,
    ip_address VARCHAR(64),
    created_by BIGINT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted INT NOT NULL DEFAULT 0,
    CONSTRAINT fk_admin_audit_logs_admin_user FOREIGN KEY (admin_user_id) REFERENCES users(id)
);

CREATE INDEX idx_admin_audit_logs_admin_user_id ON admin_audit_logs(admin_user_id);
CREATE INDEX idx_admin_audit_logs_target ON admin_audit_logs(target_type, target_id);
