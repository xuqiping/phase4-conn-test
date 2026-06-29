-- V40: 文件归属层 — stored_files 记 owner，根治 authenticated IDOR（Excel多Sheet导入设计 §10）
-- 背景：GET /api/files/{fileId} 历史零归属校验，任何登录用户 + 泄露 fileId = 读任何人文件。
--   Excel tempFileRef 在同一根因上新开注入面。→ 存储层单一咽喉点 load(fileId, userId, admin) 强校验 owner。
-- file_id = UUID+ext（即现有 fileId，FileStorageService 生成），作自然主键；不继承 BaseEntity（无自增 Long id）。
-- 生命周期：store→ACTIVE / 文档 INDEXED→CLEANED（删字节） / 删文档→删行 / PREVIEW 带 expires_at 定时清。

CREATE TABLE stored_files (
    file_id         VARCHAR(128) PRIMARY KEY,          -- UUID+ext，即现有 fileId
    tenant_id       BIGINT      NOT NULL DEFAULT 1,
    owner_user_id   BIGINT      NOT NULL,
    kb_id           BIGINT      NULL,                  -- 来源知识库（KB 场景），便于按 KB 清理
    source          VARCHAR(16) NOT NULL,              -- KB / WORKFLOW / CHAT / PREVIEW
    status          VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE / CLEANED / EXPIRED
    original_name   VARCHAR(255),
    mime            VARCHAR(128),
    size            BIGINT,
    expires_at      TIMESTAMPTZ NULL,                  -- PREVIEW 临时文件 TTL（如 now+10min）
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_stored_files_owner   ON stored_files(owner_user_id);
CREATE INDEX idx_stored_files_expires ON stored_files(expires_at) WHERE expires_at IS NOT NULL;

COMMENT ON TABLE stored_files IS '文件归属与生命周期登记。load 咽喉点据此强校验 owner，根治 IDOR。';
