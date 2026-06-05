CREATE TABLE IF NOT EXISTS system_settings (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    setting_key   VARCHAR(100) NOT NULL UNIQUE,
    setting_value TEXT NOT NULL,
    description   VARCHAR(255),
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

INSERT INTO system_settings (setting_key, setting_value, description)
VALUES ('auth.access_token_expiration_ms', '900000', 'Access Token有效期(毫秒)')
ON CONFLICT (setting_key) DO NOTHING;
