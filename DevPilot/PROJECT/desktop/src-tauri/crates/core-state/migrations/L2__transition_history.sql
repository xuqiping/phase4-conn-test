-- L2__transition_history.sql · 状态机转移历史（plan 运维清单：每次转移写历史表，可回放排查）

CREATE TABLE IF NOT EXISTS transition_history (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  project_id INTEGER NOT NULL REFERENCES projects(id),
  from_phase TEXT NOT NULL,
  to_phase   TEXT NOT NULL,
  gate       TEXT,
  actor      TEXT NOT NULL DEFAULT 'user',
  created_at TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_th_project ON transition_history(project_id);
