-- L1__init.sql · 本地库六表（权威定义：specs/db_schema.md §2）
-- 规则：本脚本一旦执行不可修改，schema 变更只能追加 L2/L3…（同 Flyway 铁律）

CREATE TABLE IF NOT EXISTS projects (
  id               INTEGER PRIMARY KEY AUTOINCREMENT,
  name             TEXT NOT NULL,
  path             TEXT NOT NULL UNIQUE,
  scale            TEXT NOT NULL DEFAULT 'L2' CHECK (scale IN ('L0','L1','L2','L3')),
  workflow_version TEXT NOT NULL,
  current_phase    TEXT NOT NULL DEFAULT 'idea',
  created_at       TEXT NOT NULL DEFAULT (datetime('now')),
  updated_at       TEXT NOT NULL DEFAULT (datetime('now'))
);

-- 状态机当前态（一项目一行，gate_status 为 JSON 快照）
CREATE TABLE IF NOT EXISTS workflow_states (
  project_id  INTEGER PRIMARY KEY REFERENCES projects(id),
  phase       TEXT NOT NULL,
  gate_status TEXT NOT NULL DEFAULT '{}',
  updated_at  TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE TABLE IF NOT EXISTS rounds (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  project_id   INTEGER NOT NULL REFERENCES projects(id),
  seq          INTEGER NOT NULL,
  title        TEXT NOT NULL DEFAULT '',
  status       TEXT NOT NULL DEFAULT 'open',
  snapshot_tag TEXT,
  created_at   TEXT NOT NULL DEFAULT (datetime('now')),
  UNIQUE (project_id, seq)
);

CREATE TABLE IF NOT EXISTS tasks (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  round_id      INTEGER NOT NULL REFERENCES rounds(id),
  chunk_no      INTEGER NOT NULL,
  title         TEXT NOT NULL,
  status        TEXT NOT NULL DEFAULT 'pending',
  source        TEXT NOT NULL DEFAULT 'local' CHECK (source IN ('local','cli','mcp','deeplink')),
  tokens_est    INTEGER,
  tokens_actual INTEGER,
  created_at    TEXT NOT NULL DEFAULT (datetime('now')),
  updated_at    TEXT NOT NULL DEFAULT (datetime('now')),
  UNIQUE (round_id, chunk_no)
);

CREATE TABLE IF NOT EXISTS checkpoints (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  task_id       INTEGER NOT NULL REFERENCES tasks(id),
  git_commit    TEXT,
  snapshot_path TEXT,
  summary_plain TEXT NOT NULL DEFAULT '',
  created_at    TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE TABLE IF NOT EXISTS artifacts (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  project_id INTEGER NOT NULL REFERENCES projects(id),
  type       TEXT NOT NULL,
  path       TEXT NOT NULL,
  version    INTEGER NOT NULL DEFAULT 1,
  created_at TEXT NOT NULL DEFAULT (datetime('now')),
  UNIQUE (project_id, type, path)
);

CREATE INDEX IF NOT EXISTS idx_tasks_round       ON tasks(round_id, status);
CREATE INDEX IF NOT EXISTS idx_checkpoints_task  ON checkpoints(task_id);
CREATE INDEX IF NOT EXISTS idx_artifacts_project ON artifacts(project_id, type);
