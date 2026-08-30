-- P03 Step3：待审批事件持久化（FR-009 两档审批模式）。
CREATE TABLE IF NOT EXISTS pending_approvals (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  project_id INTEGER NOT NULL,
  task_id INTEGER,
  kind TEXT NOT NULL CHECK (kind IN ('file', 'command')),
  title TEXT NOT NULL,
  detail TEXT NOT NULL,
  risk_level TEXT NOT NULL CHECK (risk_level IN ('normal', 'dangerous', 'critical')),
  decision TEXT CHECK (decision IN ('allow', 'deny')),
  created_at TEXT NOT NULL DEFAULT (datetime('now')),
  resolved_at TEXT
);

CREATE INDEX IF NOT EXISTS idx_pending_project ON pending_approvals (project_id, resolved_at);
CREATE INDEX IF NOT EXISTS idx_pending_task ON pending_approvals (task_id, resolved_at);
