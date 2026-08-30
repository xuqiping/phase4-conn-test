-- L9__acceptance_security.sql · 验收与安全卡点数据底座（FR-033/040/052）
-- 规则：已执行脚本不可修改；schema 演进追加 L10/L11…

-- 验收清单项：从测试方案 Markdown 解析出的可逐条核对条目
CREATE TABLE IF NOT EXISTS acceptance_items (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  project_id  INTEGER NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
  source_file TEXT    NOT NULL DEFAULT '',
  tc_id       TEXT    NOT NULL DEFAULT '',
  title       TEXT    NOT NULL DEFAULT '',
  steps       TEXT    NOT NULL DEFAULT '',
  expected    TEXT    NOT NULL DEFAULT '',
  method      TEXT    NOT NULL DEFAULT 'manual' CHECK (method IN ('auto','manual')),
  status      TEXT    NOT NULL DEFAULT 'pending' CHECK (status IN ('pending','pass','fail','na')),
  evidence_path TEXT,
  fix_task_id INTEGER REFERENCES tasks(id) ON DELETE SET NULL,
  sort_order  INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_acceptance_items_project
  ON acceptance_items(project_id, sort_order);
CREATE INDEX IF NOT EXISTS idx_acceptance_items_status
  ON acceptance_items(project_id, method, status);

-- 验收/冒烟/安全扫描运行记录（只增不改，支持历史回看）
CREATE TABLE IF NOT EXISTS acceptance_runs (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  project_id  INTEGER NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
  kind        TEXT    NOT NULL CHECK (kind IN ('checklist','smoke','security')),
  status      TEXT    NOT NULL CHECK (status IN ('pending','running','pass','fail','partial')),
  started_at  TEXT    NOT NULL DEFAULT (datetime('now')),
  finished_at TEXT,
  summary_json TEXT   NOT NULL DEFAULT '{}'
);

CREATE INDEX IF NOT EXISTS idx_acceptance_runs_project
  ON acceptance_runs(project_id, kind, started_at);

-- 安全扫描结果
CREATE TABLE IF NOT EXISTS security_scans (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  project_id  INTEGER NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
  status      TEXT    NOT NULL CHECK (status IN ('pass','fail','partial')),
  findings_json TEXT  NOT NULL DEFAULT '[]',
  started_at  TEXT    NOT NULL DEFAULT (datetime('now')),
  finished_at TEXT
);

CREATE INDEX IF NOT EXISTS idx_security_scans_project
  ON security_scans(project_id, started_at);
