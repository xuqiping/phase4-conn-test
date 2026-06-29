-- 钉钉 H5 微应用免登：用户绑定字段
-- bind_type 区分账密用户(password)与钉钉免登用户(dingtalk)
ALTER TABLE users ADD COLUMN IF NOT EXISTS bind_type        VARCHAR(20)  NOT NULL DEFAULT 'password';
ALTER TABLE users ADD COLUMN IF NOT EXISTS dingtalk_union_id VARCHAR(64);
ALTER TABLE users ADD COLUMN IF NOT EXISTS dingtalk_open_id  VARCHAR(64);

-- unionId 唯一，但允许多个 NULL（账密用户未绑定时为 NULL）。用部分索引。
CREATE UNIQUE INDEX IF NOT EXISTS uk_users_dingtalk_union_id
    ON users (dingtalk_union_id)
    WHERE dingtalk_union_id IS NOT NULL;
