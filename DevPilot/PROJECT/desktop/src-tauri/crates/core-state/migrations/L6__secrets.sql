-- L6__secrets.sql · 项目级 Secrets 表（权威定义：specs/db_schema.md §2）
-- 规则：优先走 OS 凭据管理器（keyring）；keyring 不可用时用 AES-256-GCM 加密落库。

CREATE TABLE IF NOT EXISTS secrets (
  id               INTEGER PRIMARY KEY AUTOINCREMENT,
  project_id       INTEGER NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
  name             TEXT NOT NULL,
  encrypted_value  BLOB,
  created_at       TEXT NOT NULL DEFAULT (datetime('now')),
  updated_at       TEXT NOT NULL DEFAULT (datetime('now')),
  UNIQUE (project_id, name)
);

CREATE INDEX IF NOT EXISTS idx_secrets_project ON secrets(project_id, name);
